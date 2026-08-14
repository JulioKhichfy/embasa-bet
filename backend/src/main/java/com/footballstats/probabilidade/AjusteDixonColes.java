package com.footballstats.probabilidade;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * ESTIMADOR DIXON-COLES CONJUNTO -- porte do probabilidade.model.ts.
 *
 * Estima, por maxima verossimilhanca, quatro coisas ao mesmo tempo: a forca de
 * ataque e de defesa de cada clube, a vantagem de mando e o rho da correcao de
 * placares baixos. Coordinate ascent com busca por secao aurea em cada
 * coordenada.
 *
 * POR QUE A VERSAO CONJUNTA E NAO A PARCIAL
 * -----------------------------------------
 * A estimativa de rho com lambda FIXO e monotona no intervalo valido -- ela
 * sempre encerra na borda, nunca num otimo interior, porque "mais empates" so
 * empurra rho para baixo indefinidamente. Com lambda estimado JUNTO, as forcas
 * absorvem o nivel de gols e sobra para o rho apenas a informacao que ele de
 * fato carrega: o excesso de placares baixos em relacao ao que o Poisson previa.
 * So assim o otimo e interior e o numero significa alguma coisa.
 *
 * IDENTIFICABILIDADE: ataque e defesa so sao determinados a menos de uma
 * constante -- somar 1 a todo ataque e subtrair 1 de toda defesa da exatamente
 * os mesmos lambdas. Centralizamos o ataque em zero a cada iteracao para fixar
 * a escala; sem isso os parametros passeiam e a comparacao entre ajustes de
 * temporadas diferentes perde sentido.
 *
 * CUSTO: o laco e O(maxIter x nTimes x 2 x 40 x nPartidas). Com 20 clubes e uma
 * temporada inteira sao dezenas de milhoes de avaliacoes. Ajuste por confronto
 * seria inviavel -- ajuste UMA vez por campeonato e reaproveite.
 */
public final class AjusteDixonColes {

    /** Uma partida ja indexada por posicao do clube no ajuste. */
    public record PartidaBruta(int casa, int fora, int golsCasa, int golsFora) { }

    public record ForcaClube(int indice, double ataque, double defesa) { }

    /** Resultado do ajuste. */
    public record Ajuste(List<ForcaClube> forcas, double mando, double rho,
                         double logVerossimilhanca, int iteracoes, boolean convergiu,
                         double penalidade, boolean rhoNaBorda) {

        /** Gols esperados de i (mandante) contra j (visitante). */
        public double[] golsEsperados(int i, int j) {
            if (i < 0 || j < 0 || i >= forcas.size() || j >= forcas.size()) return new double[] { 0, 0 };
            ForcaClube fi = forcas.get(i), fj = forcas.get(j);
            return new double[] {
                    Math.exp(fi.ataque() + fj.defesa() + mando),
                    Math.exp(fj.ataque() + fi.defesa())
            };
        }
    }

    /** Limites de busca do rho. Fora disto a correcao deixa de fazer sentido físico. */
    public static final double RHO_MIN = -0.30;
    public static final double RHO_MAX = 0.10;

    private AjusteDixonColes() { }

    /** ln P(x,y) sob Dixon-Coles para uma partida. */
    static double lnProbDC(int x, int y, double lambda, double mu, double rho) {
        double t = MatrizPlacares.tau(x, y, lambda, mu, rho);
        if (t <= 0) return Double.NEGATIVE_INFINITY;
        double lnPoisX = -lambda + x * Math.log(lambda) - MatrizPlacares.lnFat(x);
        double lnPoisY = -mu + y * Math.log(mu) - MatrizPlacares.lnFat(y);
        return lnPoisX + lnPoisY + Math.log(t);
    }

    /**
     * Log-verossimilhanca PENALIZADA (ridge sobre as forcas em escala log).
     *
     * penalidade = 0 reproduz o comportamento original.
     *
     * POR QUE ISTO E NECESSARIO EM CAMPEONATO CURTO
     * ---------------------------------------------
     * O modelo tem 2 parametros por clube. Com 20 clubes sao 40 forcas livres
     * mais mando e rho. Uma temporada de 190 jogos da menos de 5 observacoes por
     * parametro -- o MLE nao tem como distinguir "este ataque e forte de verdade"
     * de "este ataque teve sorte em quatro jogos", e empurra as forcas para
     * valores extremos que descrevem o passado e nao preveem nada.
     *
     * O sintoma disso NAO e viés no nivel medio de gols: e DISPERSAO DEMAIS. Com
     * forcas exageradas, alguns confrontos recebem lambda muito alto e outros
     * muito baixo; a mistura dessas Poissons tem cauda mais gorda dos DOIS lados
     * que a realidade. Na pratica o modelo prevê goleadas e 0-0 em excesso e
     * placares medios de menos -- exatamente onde mora a maioria dos jogos.
     *
     * O termo -k*(sum a^2 + sum d^2) encolhe as forcas em direcao a media da
     * liga. Clube com muitos jogos e desempenho consistente resiste ao encolhimento
     * (a verossimilhanca paga por isso); clube com amostra curta cede. E o
     * comportamento que se quer.
     */
    static double logLL(List<PartidaBruta> parts, double[] ataque, double[] defesa,
                        double mando, double rho) {
        return logLL(parts, ataque, defesa, mando, rho, 0);
    }

    static double logLL(List<PartidaBruta> parts, double[] ataque, double[] defesa,
                        double mando, double rho, double penalidade) {
        return logLL(parts, ataque, defesa, mando, rho, penalidade, null);
    }

    /**
     * @param pesos peso de cada partida na verossimilhanca; null = todos 1.
     *
     * DECAIMENTO TEMPORAL
     * -------------------
     * O Dixon-Coles original pondera cada partida por exp(-xi * dias_atras).
     * A razao e simples: forca de clube nao e constante. Lesao, troca de
     * tecnico, janela de transferencia e forma mudam o time ao longo da
     * temporada, e sem ponderacao o jogo da rodada 1 pesa igual ao da rodada 20.
     *
     * O efeito colateral e que o peso EFETIVO da amostra encolhe -- com xi alto
     * sobram poucas partidas realmente influentes, e a variancia das forcas
     * sobe. Existe um otimo, e ele nao e adivinhavel: tem que sair de
     * validacao fora da amostra.
     */
    static double logLL(List<PartidaBruta> parts, double[] ataque, double[] defesa,
                        double mando, double rho, double penalidade, double[] pesos) {
        double s = 0;
        for (int k = 0; k < parts.size(); k++) {
            PartidaBruta p = parts.get(k);
            double lambda = Math.exp(ataque[p.casa()] + defesa[p.fora()] + mando);
            double mu     = Math.exp(ataque[p.fora()] + defesa[p.casa()]);
            double l = lnProbDC(p.golsCasa(), p.golsFora(), lambda, mu, rho);
            if (l == Double.NEGATIVE_INFINITY) return Double.NEGATIVE_INFINITY;
            s += (pesos == null ? l : l * pesos[k]);
        }
        if (penalidade > 0) {
            double pen = 0;
            for (int i = 0; i < ataque.length; i++) pen += ataque[i] * ataque[i] + defesa[i] * defesa[i];
            s -= penalidade * pen;
        }
        return s;
    }

    /** Secao aurea: valor de x em [a,b] que maximiza f. */
    static double maximiza1D(DoubleUnaryOperator f, double a, double b, int iter) {
        double gr = (Math.sqrt(5) - 1) / 2;
        double c = b - gr * (b - a);
        double d = a + gr * (b - a);
        double fc = f.applyAsDouble(c), fd = f.applyAsDouble(d);
        for (int i = 0; i < iter && (b - a) > 1e-6; i++) {
            if (fc < fd) { a = c; c = d; fc = fd; d = a + gr * (b - a); fd = f.applyAsDouble(d); }
            else         { b = d; d = c; fd = fc; c = b - gr * (b - a); fc = f.applyAsDouble(c); }
        }
        return (a + b) / 2;
    }

    /** Penalidade padrao. Ver {@link #penalidadeSugerida}. */
    public static Ajuste estimar(int nTimes, List<PartidaBruta> partidas) {
        return estimar(nTimes, partidas, 60, penalidadeSugerida(nTimes, partidas.size()));
    }

    /** Sem regularizacao -- so para reproduzir o comportamento antigo. */
    public static Ajuste estimarSemPenalidade(int nTimes, List<PartidaBruta> partidas) {
        return estimar(nTimes, partidas, 60, 0);
    }

    /**
     * Penalidade em funcao da razao observacoes/parametro.
     *
     * A regularizacao precisa ser forte onde ha pouco dado e desaparecer quando
     * ha muito -- senao ela vira vies permanente. Com 2 parametros por clube,
     * obs/param = partidas/(2*nTimes). A forma abaixo encolhe bastante abaixo de
     * ~10 obs/param e some acima de ~30.
     *
     * A constante foi escolhida por experimento fora da amostra, nao por teoria.
     * Se um dia houver base grande o bastante, troque por validacao cruzada.
     */
    public static double penalidadeSugerida(int nTimes, int nPartidas) {
        if (nTimes <= 0 || nPartidas <= 0) return 0;
        double obsPorParam = nPartidas / (2.0 * nTimes);
        if (obsPorParam >= 30) return 0;
        return 12.0 * (1.0 / Math.max(1.0, obsPorParam) - 1.0 / 30.0);
    }

    public static Ajuste estimar(int nTimes, List<PartidaBruta> partidas, int maxIter, double penalidade) {
        return estimar(nTimes, partidas, maxIter, penalidade, null);
    }

    /**
     * Pesos exponenciais a partir da idade de cada partida em dias.
     *
     * xi = 0 devolve null (sem ponderacao). Referencia: xi ~ 0,0065/dia da
     * literatura equivale a meia-vida de ~107 dias.
     */
    public static double[] pesosPorIdade(double[] diasAtras, double xi) {
        if (xi <= 0 || diasAtras == null) return null;
        double[] w = new double[diasAtras.length];
        for (int i = 0; i < w.length; i++) w[i] = Math.exp(-xi * Math.max(0, diasAtras[i]));
        return w;
    }

    public static Ajuste estimar(int nTimes, List<PartidaBruta> partidas, int maxIter,
                                 double penalidade, double[] pesos) {
        double[] ataque = new double[nTimes];
        double[] defesa = new double[nTimes];
        double mando = 0.1;
        double rho = MatrizPlacares.Params.padrao().rho();

        if (partidas.isEmpty() || nTimes == 0) {
            return new Ajuste(List.of(), mando, rho, 0, 0, false, penalidade, false);
        }

        double llAnt = logLL(partidas, ataque, defesa, mando, rho, penalidade, pesos);
        int it = 0;
        boolean convergiu = false;

        for (; it < maxIter; it++) {
            final double mandoIter = mando;
            final double rhoIter = rho;
            for (int i = 0; i < nTimes; i++) {
                final int idx = i;
                ataque[i] = maximiza1D(v -> {
                    double bak = ataque[idx]; ataque[idx] = v;
                    double l = logLL(partidas, ataque, defesa, mandoIter, rhoIter, penalidade, pesos);
                    ataque[idx] = bak; return l;
                }, -3, 3, 40);
                defesa[i] = maximiza1D(v -> {
                    double bak = defesa[idx]; defesa[idx] = v;
                    double l = logLL(partidas, ataque, defesa, mandoIter, rhoIter, penalidade, pesos);
                    defesa[idx] = bak; return l;
                }, -3, 3, 40);
            }
            // centraliza o ataque: fixa a escala (ver nota de identificabilidade)
            double media = 0;
            for (double v : ataque) media += v;
            media /= nTimes;
            for (int i = 0; i < nTimes; i++) ataque[i] -= media;

            final double rhoAtual = rho;
            mando = maximiza1D(v -> logLL(partidas, ataque, defesa, v, rhoAtual, penalidade, pesos), -1, 1, 40);
            final double mandoAtual = mando;
            rho = maximiza1D(v -> logLL(partidas, ataque, defesa, mandoAtual, v, penalidade, pesos), RHO_MIN, RHO_MAX, 40);

            double ll = logLL(partidas, ataque, defesa, mando, rho, penalidade, pesos);
            if (Math.abs(ll - llAnt) < 1e-5) { convergiu = true; llAnt = ll; it++; break; }
            llAnt = ll;
        }

        List<ForcaClube> forcas = new ArrayList<>(nTimes);
        for (int i = 0; i < nTimes; i++) forcas.add(new ForcaClube(i, ataque[i], defesa[i]));

        // rho encostado no limite nao e uma estimativa, e um pedido de socorro:
        // significa que o modelo esta usando rho para compensar algo que rho nao
        // descreve. Sinalizamos em vez de fingir que o numero e valido.
        boolean naBorda = rho > RHO_MAX - 1e-3 || rho < RHO_MIN + 1e-3;
        return new Ajuste(forcas, mando, rho, llAnt, it, convergiu, penalidade, naBorda);
    }
}