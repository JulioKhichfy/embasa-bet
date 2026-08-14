package com.footballstats.service;

import com.footballstats.model.Partida;
import com.footballstats.probabilidade.AjusteDixonColes;
import com.footballstats.probabilidade.AjusteDixonColes.Ajuste;
import com.footballstats.probabilidade.AjusteDixonColes.PartidaBruta;
import com.footballstats.probabilidade.Calibracao;
import com.footballstats.probabilidade.MatrizPlacares;
import com.footballstats.probabilidade.MercadoGols;
import com.footballstats.repository.PartidaRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BACKTEST WALK-FORWARD das projecoes.
 *
 * Responde a unica pergunta que importa antes de apostar dinheiro: quando o
 * modelo diz 60%, acontece 60%?
 *
 * A REGRA QUE NAO PODE SER QUEBRADA: VAZAMENTO
 * ---------------------------------------------
 * Cada partida e prevista usando SO as partidas ANTERIORES a ela. Ajustar no
 * conjunto inteiro e depois "testar" nele daria um resultado excelente e
 * completamente falso -- o modelo ja teria visto o resultado que esta tentando
 * prever. E o erro mais comum em avaliacao de modelo, e o mais caro aqui,
 * porque produz confianca onde nao ha.
 *
 * Por isso a ordenacao e por DATA e a janela e expansiva.
 *
 * CUSTO E O REAJUSTE PERIODICO
 * ----------------------------
 * Reajustar a cada partida seria o ideal estatistico e custaria centenas de
 * milissegundos vezes o numero de partidas -- minutos por campeonato. Como as
 * forcas mudam devagar (uma partida a mais em 300 quase nao move o ajuste),
 * reajustamos a cada `passoReajuste` partidas. E uma aproximacao consciente:
 * ela EMPIORA levemente o resultado medido, nunca melhora, entao nao infla a
 * nota do modelo.
 *
 * A FASE DE AQUECIMENTO tambem e descartada: as primeiras partidas nao tem
 * historico suficiente e produziriam previsoes absurdas que sujariam a metrica
 * sem dizer nada sobre o modelo em uso normal.
 */
@Service
public class BacktestService {

    private final PartidaRepository partidaRepo;

    public BacktestService(PartidaRepository partidaRepo) {
        this.partidaRepo = partidaRepo;
    }

    public static class Relatorio {
        public boolean sucesso;
        public String mensagem;
        public String modelo;
        public int partidasTotais;
        public int partidasAvaliadas;
        public int reajustes;
        public long duracaoMs;
        /** Regularização aplicada; 0 = nenhuma. */
        public double penalidade;
        /** rho médio dos ajustes. Em futebol espera-se algo entre -0.15 e 0. */
        public double rhoMedio;
        /**
         * Quantos ajustes terminaram com rho encostado no limite de busca.
         * Acima de zero é sinal de má especificação: o rho está sendo usado para
         * compensar algo que ele não descreve — quase sempre dispersão a mais nas
         * forças de ataque/defesa.
         */
        public int rhoNaBorda;
        public long duracaoMsPorReajuste;
        /** codigo do mercado -> metricas */
        public Map<String, Calibracao.Resultado> porMercado = new LinkedHashMap<>();
        /** codigo do mercado -> intervalo de confianca do BSS */
        public Map<String, Calibracao.IntervaloBSS> intervalos = new LinkedHashMap<>();
        /** campeonatos que entraram (quando a rodada e agregada) */
        public List<String> campeonatos = new ArrayList<>();
        /**
         * Metade da largura do IC95 do BSS, media entre mercados.
         *
         * E a RESOLUCAO do teste: efeitos menores que isto sao invisiveis nesta
         * amostra, por melhor ou pior que o modelo seja. Skill realista em
         * mercado de gols fica na casa de 0,02 a 0,06 -- se a resolucao for
         * maior que isso, o backtest nao esta medindo o modelo, esta medindo
         * ruido.
         */
        public double resolucao;
        public String leitura;

        static Relatorio falha(String m) {
            Relatorio r = new Relatorio();
            r.sucesso = false;
            r.mensagem = m;
            return r;
        }
    }

    /** Mercados avaliados e o predicado que decide se o evento ocorreu. */
    private interface Desfecho { boolean ocorreu(int golsCasa, int golsFora); }

    private record MercadoTeste(String nome, Desfecho desfecho,
                                java.util.function.Function<MercadoGols, Double> probabilidade) { }

    private static final List<MercadoTeste> TESTES = List.of(
            new MercadoTeste("btts",          (c, f) -> c >= 1 && f >= 1,        MercadoGols::btts),
            new MercadoTeste("over_0",        (c, f) -> c + f > 0,               g -> g.over(0)),
            new MercadoTeste("over_1",        (c, f) -> c + f > 1,               g -> g.over(1)),
            new MercadoTeste("over_2",        (c, f) -> c + f > 2,               g -> g.over(2)),
            new MercadoTeste("over_3",        (c, f) -> c + f > 3,               g -> g.over(3)),
            new MercadoTeste("over_4",        (c, f) -> c + f > 4,               g -> g.over(4)),
            new MercadoTeste("faixa_1_2",     (c, f) -> c + f >= 1 && c + f <= 2, g -> g.faixaGols(1, 2)),
            new MercadoTeste("faixa_1_3",     (c, f) -> c + f >= 1 && c + f <= 3, g -> g.faixaGols(1, 3)),
            new MercadoTeste("margem_2mais",  (c, f) -> Math.abs(c - f) >= 2,    g -> g.margem(1)),
            new MercadoTeste("margem_3mais",  (c, f) -> Math.abs(c - f) >= 3,    g -> g.margem(2)),
            new MercadoTeste("vitoria_casa",  (c, f) -> c > f,                   g -> g.onde(MercadoGols.vitoriaCasa())),
            new MercadoTeste("empate",        (c, f) -> c == f,                  g -> g.onde(MercadoGols.empate()))
    );

    /**
     * @param campeonatoId    campeonato a avaliar
     * @param aquecimento     partidas iniciais descartadas (default 60)
     * @param passoReajuste   de quantas em quantas partidas refazer o ajuste (default 10)
     */
    public Relatorio rodar(Long campeonatoId, MatrizPlacares.Modelo modelo,
                           int aquecimento, int passoReajuste) {
        return rodarInterno(campeonatoId, modelo, aquecimento, passoReajuste, null);
    }

    /**
     * @param acumulado quando nao-nulo, os pontos tambem sao despejados aqui
     *                  para agregacao entre campeonatos
     */
    private Relatorio rodarInterno(Long campeonatoId, MatrizPlacares.Modelo modelo,
                                   int aquecimento, int passoReajuste,
                                   Map<String, List<Calibracao.Ponto>> acumulado) {
        return rodarInterno(campeonatoId, modelo, aquecimento, passoReajuste, acumulado, -1, 0);
    }

    /**
     * @param penalidadeFixa valor de ridge; negativo usa penalidadeSugerida
     * @param xi             decaimento temporal por dia; 0 desliga
     */
    private Relatorio rodarInterno(Long campeonatoId, MatrizPlacares.Modelo modelo,
                                   int aquecimento, int passoReajuste,
                                   Map<String, List<Calibracao.Ponto>> acumulado,
                                   double penalidadeFixa, double xi) {

        long t0 = System.currentTimeMillis();

        List<Partida> todas = new ArrayList<>(partidaRepo.findByCampeonato(campeonatoId));
        todas.removeIf(p -> p.getGolsCasa() == null || p.getGolsFora() == null);
        todas.sort(Comparator.comparing(Partida::getData));

        if (todas.size() < aquecimento + 50) {
            return Relatorio.falha("Só " + todas.size() + " partida(s) com placar. "
                    + "Para um backtest que diga alguma coisa, queira pelo menos "
                    + (aquecimento + 300) + ".");
        }

        Map<String, List<Calibracao.Ponto>> pontos = new LinkedHashMap<>();
        for (MercadoTeste t : TESTES) pontos.put(t.nome(), new ArrayList<>());

        Map<Long, Integer> indices = new HashMap<>();
        List<PartidaBruta> historico = new ArrayList<>();
        List<LocalDate> datas = new ArrayList<>();

        // aquecimento: entra no histórico, não é avaliado
        for (int i = 0; i < aquecimento; i++) {
            adicionar(todas.get(i), indices, historico);
            datas.add(todas.get(i).getData());
        }

        Ajuste ajuste = null;
        int desdeReajuste = Integer.MAX_VALUE;
        int reajustes = 0, avaliadas = 0, naBorda = 0;
        double somaRho = 0, penalidadeUsada = 0;

        for (int i = aquecimento; i < todas.size(); i++) {
            Partida p = todas.get(i);

            if (desdeReajuste >= passoReajuste) {
                ajuste = AjusteDixonColes.estimar(indices.size(), historico, 60,
                        penalidadeFixa >= 0 ? penalidadeFixa
                                : AjusteDixonColes.penalidadeSugerida(indices.size(), historico.size()),
                        pesosAte(datas, p.getData(), xi));
                reajustes++;
                desdeReajuste = 0;
                somaRho += ajuste.rho();
                penalidadeUsada = ajuste.penalidade();
                if (ajuste.rhoNaBorda()) naBorda++;
            }

            Integer ic = indices.get(p.getClubeCasa().getId());
            Integer ifo = indices.get(p.getClubeFora().getId());

            // Clube que ainda não apareceu no histórico não pode ser previsto.
            // Prever com força zero seria inventar informação.
            if (ic != null && ifo != null && ajuste != null && !ajuste.forcas().isEmpty()
                    && ic < ajuste.forcas().size() && ifo < ajuste.forcas().size()) {

                double[] lam = ajuste.golsEsperados(ic, ifo);
                MatrizPlacares.Params params = MatrizPlacares.Params.padrao().comRho(ajuste.rho());
                MercadoGols g = MercadoGols.de(modelo, lam[0], lam[1], params);

                int gc = p.getGolsCasa(), gf = p.getGolsFora();
                for (MercadoTeste t : TESTES) {
                    Calibracao.Ponto pt = new Calibracao.Ponto(
                            t.probabilidade().apply(g), t.desfecho().ocorreu(gc, gf));
                    pontos.get(t.nome()).add(pt);
                    if (acumulado != null) acumulado.get(t.nome()).add(pt);
                }
                avaliadas++;
            }

            adicionar(p, indices, historico);
            datas.add(p.getData());
            desdeReajuste++;
        }

        Relatorio r = new Relatorio();
        r.sucesso = true;
        r.modelo = modelo.name();
        r.partidasTotais = todas.size();
        r.partidasAvaliadas = avaliadas;
        r.reajustes = reajustes;
        r.penalidade = penalidadeUsada;
        r.rhoMedio = reajustes > 0 ? somaRho / reajustes : 0;
        r.rhoNaBorda = naBorda;
        finalizar(r, pontos);
        r.duracaoMs = System.currentTimeMillis() - t0;
        r.duracaoMsPorReajuste = reajustes > 0 ? r.duracaoMs / reajustes : 0;
        r.mensagem = avaliadas + " partida(s) avaliadas fora da amostra, "
                + reajustes + " reajuste(s), em " + r.duracaoMs + " ms.";
        return r;
    }

    private void finalizar(Relatorio r, Map<String, List<Calibracao.Ponto>> pontos) {
        double somaLargura = 0;
        int mercados = 0;
        for (Map.Entry<String, List<Calibracao.Ponto>> e : pontos.entrySet()) {
            r.porMercado.put(e.getKey(), Calibracao.avaliar(e.getValue()));
            Calibracao.IntervaloBSS iv = Calibracao.bootstrapBSS(e.getValue());
            r.intervalos.put(e.getKey(), iv);
            somaLargura += (iv.superior() - iv.inferior()) / 2;
            mercados++;
        }
        r.resolucao = mercados > 0 ? somaLargura / mercados : 0;

        long comSkill = r.intervalos.values().stream().filter(i -> i.inferior() > 0).count();
        long semSkill = r.intervalos.values().stream().filter(i -> i.superior() < 0).count();
        if (comSkill > 0) {
            r.leitura = comSkill + " mercado(s) com skill demonstrado (IC95 acima de zero).";
        } else if (semSkill > 0) {
            r.leitura = semSkill + " mercado(s) comprovadamente PIORES que a taxa base.";
        } else {
            r.leitura = String.format(
                    "Nenhum mercado é distinguível da taxa base. Resolução do teste: ±%.3f de BSS."
                            + " Skill realista em gols fica entre 0,02 e 0,06, então esta amostra não"
                            + " conseguiria enxergar um modelo bom mesmo que ele fosse bom."
                            + " O gargalo é DADO, não modelo.", r.resolucao);
        }
    }

    /**
     * Backtest AGREGADO sobre varios campeonatos.
     *
     * Cada campeonato e ajustado SEPARADAMENTE -- forcas de ataque de ligas
     * diferentes nao sao comparaveis e misturar as partidas produziria um
     * modelo que nao descreve nenhuma delas. Mas a AVALIACAO pode ser somada:
     * "quando o modelo disse 60%, aconteceu 60%?" e a mesma pergunta em
     * qualquer liga.
     *
     * E o unico jeito de sair de 86 casos sem esperar tres temporadas. Com
     * quatro campeonatos de meia temporada voce chega perto de 350 avaliacoes,
     * que ja e faixa de teste com poder.
     */
    public Relatorio rodarAgregado(List<Long> campeonatoIds, MatrizPlacares.Modelo modelo,
                                   int aquecimento, int passoReajuste) {
        return rodarAgregado(campeonatoIds, modelo, aquecimento, passoReajuste, -1, 0);
    }

    public Relatorio rodarAgregado(List<Long> campeonatoIds, MatrizPlacares.Modelo modelo,
                                   int aquecimento, int passoReajuste,
                                   double penalidadeFixa, double xi) {
        long t0 = System.currentTimeMillis();
        Map<String, List<Calibracao.Ponto>> acumulado = new LinkedHashMap<>();
        for (MercadoTeste t : TESTES) acumulado.put(t.nome(), new ArrayList<>());

        Relatorio agregado = new Relatorio();
        int totais = 0, avaliadas = 0, reajustes = 0;
        double somaRho = 0, penalidade = 0;
        int comRho = 0, naBorda = 0;

        for (Long id : campeonatoIds) {
            Relatorio parcial = rodarInterno(id, modelo, aquecimento, passoReajuste, acumulado,
                    penalidadeFixa, xi);
            if (!parcial.sucesso) continue;
            agregado.campeonatos.add("campeonato " + id + ": " + parcial.partidasAvaliadas + " avaliada(s)");
            totais += parcial.partidasTotais;
            avaliadas += parcial.partidasAvaliadas;
            reajustes += parcial.reajustes;
            somaRho += parcial.rhoMedio;
            naBorda += parcial.rhoNaBorda;
            penalidade = parcial.penalidade;
            comRho++;
        }

        if (avaliadas == 0) {
            return Relatorio.falha("Nenhum campeonato tinha histórico suficiente.");
        }

        agregado.sucesso = true;
        agregado.modelo = modelo.name();
        agregado.partidasTotais = totais;
        agregado.partidasAvaliadas = avaliadas;
        agregado.reajustes = reajustes;
        agregado.penalidade = penalidade;
        agregado.rhoMedio = comRho > 0 ? somaRho / comRho : 0;
        agregado.rhoNaBorda = naBorda;
        finalizar(agregado, acumulado);
        agregado.duracaoMs = System.currentTimeMillis() - t0;
        agregado.duracaoMsPorReajuste = reajustes > 0 ? agregado.duracaoMs / reajustes : 0;
        agregado.mensagem = avaliadas + " partida(s) avaliadas em " + agregado.campeonatos.size()
                + " campeonato(s), " + reajustes + " reajuste(s), em " + agregado.duracaoMs + " ms.";
        return agregado;
    }

    // ==================================================================
    // Varredura de hiperparâmetros
    // ==================================================================

    public record Config(double penalidade, double xi) { }

    public static class LinhaVarredura {
        public double penalidade;
        public double xi;
        public int n;
        /** BSS médio entre os mercados testados. */
        public double bssMedio;
        /** Quantos mercados terminaram com IC95 inteiramente acima de zero. */
        public int mercadosComSkill;
        public double eceRelativoMedio;
        public double rhoMedio;
        public int rhoNaBorda;
        public long duracaoMs;
    }

    public static class Varredura {
        public boolean sucesso;
        public String mensagem;
        public List<LinhaVarredura> linhas = new ArrayList<>();
        public double resolucao;
        /**
         * Quanto o MELHOR BSS da grade está inflado só por escolher o máximo de
         * várias tentativas ruidosas. Ver {@link #aviso}.
         */
        public double vieselecao;
        public String aviso;
    }

    /**
     * Varre a grade (penalidade x decaimento) avaliando cada config fora da amostra.
     *
     * CUIDADO COM O QUE ISTO PODE E NAO PODE DIZER
     * --------------------------------------------
     * Escolher o maior BSS de uma grade de K configuracoes NAO devolve o melhor
     * modelo -- devolve o modelo que teve mais sorte no conjunto de teste. Com
     * resolucao de +-0,04 e uma grade de 16, o maximo esperado sob a hipotese de
     * que todas sao iguais ja fica ~0,07 acima da media, e isso e MAIOR que o
     * efeito que estamos procurando (skill realista fica entre 0,02 e 0,06).
     *
     * Entao o uso legitimo desta varredura e DIAGNOSTICO, nao selecao:
     *
     *   - Existe um PLATO suave? Uma regiao larga de configs boas e sinal real;
     *     um pico isolado cercado de vales e ruido.
     *   - O decaimento temporal move a agulha de forma consistente em toda a
     *     faixa de penalidade, ou so num ponto?
     *   - O rho para de encostar na borda em alguma regiao?
     *
     * Escolher a config pelo maximo desta grade e depois reportar o BSS dela como
     * evidencia e a versao lenta de vazamento de dados.
     */
    public Varredura varrer(List<Long> campeonatoIds, MatrizPlacares.Modelo modelo,
                            int aquecimento, int passoReajuste,
                            double[] penalidades, double[] decaimentos) {

        Varredura v = new Varredura();
        for (double pen : penalidades) {
            for (double xi : decaimentos) {
                long t0 = System.currentTimeMillis();
                Relatorio r = rodarAgregado(campeonatoIds, modelo, aquecimento, passoReajuste, pen, xi);
                if (!r.sucesso) continue;

                LinhaVarredura l = new LinhaVarredura();
                l.penalidade = pen;
                l.xi = xi;
                l.n = r.partidasAvaliadas;
                l.rhoMedio = r.rhoMedio;
                l.rhoNaBorda = r.rhoNaBorda;
                l.duracaoMs = System.currentTimeMillis() - t0;

                double somaBss = 0, somaEce = 0;
                int m = 0, comSkill = 0;
                for (Map.Entry<String, Calibracao.Resultado> e : r.porMercado.entrySet()) {
                    somaBss += e.getValue().brierSkillScore();
                    somaEce += e.getValue().eceRelativo();
                    m++;
                    Calibracao.IntervaloBSS iv = r.intervalos.get(e.getKey());
                    if (iv != null && iv.inferior() > 0) comSkill++;
                }
                l.bssMedio = m > 0 ? somaBss / m : 0;
                l.eceRelativoMedio = m > 0 ? somaEce / m : 0;
                l.mercadosComSkill = comSkill;
                v.linhas.add(l);
                v.resolucao = r.resolucao;
            }
        }

        if (v.linhas.isEmpty()) {
            v.sucesso = false;
            v.mensagem = "Nenhuma configuração produziu resultado; histórico insuficiente.";
            return v;
        }

        v.sucesso = true;
        int k = v.linhas.size();
        // Maximo esperado de k normais padrao ~ sqrt(2 ln k); em unidades de
        // erro-padrao do BSS, que e resolucao/1,96.
        double erroPadrao = v.resolucao / 1.96;
        v.vieselecao = erroPadrao * Math.sqrt(2 * Math.log(Math.max(2, k)));
        v.mensagem = k + " configuração(ões) avaliadas sobre " + v.linhas.get(0).n + " partidas.";
        v.aviso = String.format(
                "Escolher o máximo desta grade infla o BSS em ~%.3f só por seleção (k=%d,"
                        + " resolução ±%.3f). Procure PLATÔ, não pico: uma região larga de configs boas"
                        + " é sinal; um máximo isolado é sorte.", v.vieselecao, k, v.resolucao);
        return v;
    }

    /**
     * Pesos exponenciais pela idade de cada partida histórica na data de referência.
     *
     * Usa toEpochDay() em vez de ChronoUnit.DAYS.between(): é a mesma conta
     * (LocalDate já guarda o dia como inteiro internamente), dispensa o pacote
     * java.time.temporal e evita a chamada por elemento num laço que roda a cada
     * reajuste.
     */
    private double[] pesosAte(List<LocalDate> datas, LocalDate ref, double xi) {
        if (xi <= 0) return null;
        long diaRef = ref.toEpochDay();
        double[] dias = new double[datas.size()];
        for (int i = 0; i < dias.length; i++) {
            dias[i] = diaRef - datas.get(i).toEpochDay();
        }
        return AjusteDixonColes.pesosPorIdade(dias, xi);
    }

    private void adicionar(Partida p, Map<Long, Integer> indices, List<PartidaBruta> hist) {
        int ic = indices.computeIfAbsent(p.getClubeCasa().getId(), k -> indices.size());
        int ifo = indices.computeIfAbsent(p.getClubeFora().getId(), k -> indices.size());
        hist.add(new PartidaBruta(ic, ifo, p.getGolsCasa(), p.getGolsFora()));
    }
}