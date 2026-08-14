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

        // aquecimento: entra no histórico, não é avaliado
        for (int i = 0; i < aquecimento; i++) adicionar(todas.get(i), indices, historico);

        Ajuste ajuste = null;
        int desdeReajuste = Integer.MAX_VALUE;
        int reajustes = 0, avaliadas = 0, naBorda = 0;
        double somaRho = 0, penalidadeUsada = 0;

        for (int i = aquecimento; i < todas.size(); i++) {
            Partida p = todas.get(i);

            if (desdeReajuste >= passoReajuste) {
                ajuste = AjusteDixonColes.estimar(indices.size(), historico);
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
                    pontos.get(t.nome()).add(new Calibracao.Ponto(
                            t.probabilidade().apply(g), t.desfecho().ocorreu(gc, gf)));
                }
                avaliadas++;
            }

            adicionar(p, indices, historico);
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
        pontos.forEach((nome, lista) -> r.porMercado.put(nome, Calibracao.avaliar(lista)));
        r.duracaoMs = System.currentTimeMillis() - t0;
        r.duracaoMsPorReajuste = reajustes > 0 ? r.duracaoMs / reajustes : 0;
        r.mensagem = avaliadas + " partida(s) avaliadas fora da amostra, "
                + reajustes + " reajuste(s), em " + r.duracaoMs + " ms.";
        return r;
    }

    private void adicionar(Partida p, Map<Long, Integer> indices, List<PartidaBruta> hist) {
        int ic = indices.computeIfAbsent(p.getClubeCasa().getId(), k -> indices.size());
        int ifo = indices.computeIfAbsent(p.getClubeFora().getId(), k -> indices.size());
        hist.add(new PartidaBruta(ic, ifo, p.getGolsCasa(), p.getGolsFora()));
    }
}