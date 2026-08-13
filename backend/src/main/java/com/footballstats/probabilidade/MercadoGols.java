package com.footballstats.probabilidade;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * MERCADOS DE GOLS: recortes da matriz de placares.
 *
 * A ideia central desta classe e que ambos marcam, total de gols, faixa de gols
 * e margem de vitoria NAO sao quatro modelos. Sao quatro PERGUNTAS diferentes
 * feitas a mesma matriz conjunta. Cada uma e a soma das celulas (i,j) que
 * satisfazem um predicado. Nenhuma matematica nova alem do Dixon-Coles que ja
 * estava pronto.
 *
 * A CONSEQUENCIA QUE MAIS IMPORTA: MULTIPLAS CORRELACIONADAS
 * -----------------------------------------------------------
 * "Mais de 2.5 gols" e "ambos marcam" no MESMO jogo nao sao independentes --
 * quase todo placar com 3+ gols tem os dois times marcando. Multiplicar as duas
 * probabilidades subestima grosseiramente a combinada e faz a aposta parecer um
 * valor que nao existe.
 *
 * Como a matriz e conjunta, a resposta certa e trivial: some as celulas que
 * satisfazem OS DOIS predicados ao mesmo tempo. E o que {@link #conjunta} faz.
 * Casas de aposta cobram caro por bet builder exatamente porque a maioria dos
 * apostadores multiplica.
 *
 * CONVENCAO DE LINHA: piso EXCLUSIVO em todo mercado OVER, ou seja
 * P(X > linha). "Mais de 2" e P(total >= 3). O MapeamentoMercado ja converte o
 * vocabulario da casa para essa convencao.
 */
public final class MercadoGols {

    private final double[][] m;
    private final int n;

    public MercadoGols(double[][] matrizNormalizada) {
        this.m = matrizNormalizada;
        this.n = matrizNormalizada.length - 1;
    }

    public static MercadoGols de(MatrizPlacares.Modelo modelo, double lc, double lf,
                                 MatrizPlacares.Params p) {
        return new MercadoGols(MatrizPlacares.normalizada(modelo, lc, lf, p));
    }

    // ------------------------------------------------------------------
    // Primitiva
    // ------------------------------------------------------------------

    /** Soma das celulas (i,j) que satisfazem o predicado. Base de tudo aqui. */
    public double onde(BiPredicate<Integer, Integer> cond) {
        double s = 0;
        for (int i = 0; i <= n; i++)
            for (int j = 0; j <= n; j++)
                if (cond.test(i, j)) s += m[i][j];
        return s;
    }

    /**
     * Probabilidade de DOIS mercados baterem no mesmo jogo.
     *
     * Use sempre isto para montar multipla do mesmo confronto, nunca o produto
     * das probabilidades individuais.
     */
    public double conjunta(BiPredicate<Integer, Integer> a, BiPredicate<Integer, Integer> b) {
        return onde((i, j) -> a.test(i, j) && b.test(i, j));
    }

    // ------------------------------------------------------------------
    // Predicados nomeados -- combinaveis via conjunta()
    // ------------------------------------------------------------------

    public static BiPredicate<Integer, Integer> ambosMarcam() {
        return (i, j) -> i >= 1 && j >= 1;
    }

    /** P(total > linha). "Mais de 2" -> linha 2. */
    public static BiPredicate<Integer, Integer> totalAcimaDe(double linha) {
        return (i, j) -> (i + j) > linha;
    }

    /** Faixa fechada: de <= total <= ate. O 0-0 so entra se de == 0. */
    public static BiPredicate<Integer, Integer> faixa(double de, double ate) {
        return (i, j) -> (i + j) >= de && (i + j) <= ate;
    }

    /** P(|i-j| > linha). "2 ou mais gols de margem" -> linha 1. */
    public static BiPredicate<Integer, Integer> margemAcimaDe(double linha) {
        return (i, j) -> Math.abs(i - j) > linha;
    }

    /** Margem de um lado especifico: mandante vencendo por mais de `linha`. */
    public static BiPredicate<Integer, Integer> margemCasaAcimaDe(double linha) {
        return (i, j) -> (i - j) > linha;
    }

    public static BiPredicate<Integer, Integer> margemForaAcimaDe(double linha) {
        return (i, j) -> (j - i) > linha;
    }

    public static BiPredicate<Integer, Integer> vitoriaCasa() { return (i, j) -> i > j; }
    public static BiPredicate<Integer, Integer> empate()      { return (i, j) -> i.equals(j); }
    public static BiPredicate<Integer, Integer> vitoriaFora() { return (i, j) -> j > i; }

    // ------------------------------------------------------------------
    // Atalhos
    // ------------------------------------------------------------------

    public double btts()                    { return onde(ambosMarcam()); }
    public double over(double linha)        { return onde(totalAcimaDe(linha)); }
    public double faixaGols(double de, double ate) { return onde(faixa(de, ate)); }
    public double margem(double linha)      { return onde(margemAcimaDe(linha)); }

    /** Placar mais provavel, como {casa, fora}. */
    public int[] placarModal() {
        double melhor = -1;
        int pi = 0, pj = 0;
        for (int i = 0; i <= n; i++)
            for (int j = 0; j <= n; j++)
                if (m[i][j] > melhor) { melhor = m[i][j]; pi = i; pj = j; }
        return new int[] { pi, pj };
    }

    /** Gols esperados totais, direto da matriz (confere o truncamento em MAXG). */
    public double golsEsperados() {
        double s = 0;
        for (int i = 0; i <= n; i++)
            for (int j = 0; j <= n; j++) s += (i + j) * m[i][j];
        return s;
    }

    // ------------------------------------------------------------------
    // Mercados por tempo
    // ------------------------------------------------------------------

    /**
     * Fracao dos gols que sai no PRIMEIRO tempo.
     *
     * ~44% e o valor tipico no futebol de clubes: o segundo tempo produz mais
     * gols por cansaco, substituicoes e times perdendo que se expoem. E um
     * prior, nao uma constante universal -- assim que houver placar de intervalo
     * suficiente na base, estime pela sua propria amostra e substitua.
     */
    public static final double FRACAO_1T = 0.44;

    /**
     * Ambos marcam num tempo especifico.
     *
     * Aqui NAO da para usar a matriz da partida: ela e sobre o placar final, e
     * "ambos marcaram no primeiro tempo" e uma pergunta sobre um recorte que a
     * matriz nao carrega. Montamos uma matriz propria do tempo com lambda
     * escalado.
     *
     * Sem correcao Dixon-Coles: o tau foi calibrado sobre placares finais, e
     * placar de meio-tempo tem outra distribuicao. Aplica-lo seria transportar
     * uma correcao para fora do dominio em que foi medida.
     */
    public static double bttsNoTempo(double lc, double lf, boolean primeiroTempo) {
        double f = primeiroTempo ? FRACAO_1T : (1 - FRACAO_1T);
        double pc = 1 - Math.exp(-lc * f);   // P(casa marca ao menos 1)
        double pf = 1 - Math.exp(-lf * f);
        return pc * pf;
    }

    /**
     * "Tempo com mais gols": P(1º), P(empate) e P(2º).
     *
     * Convolucao dos totais de cada tempo, tratados como Poisson independentes.
     * A independencia entre tempos e uma simplificacao -- a correlacao real e
     * levemente negativa (jogo que explode no 1T tende a esfriar) -- mas o erro
     * e pequeno perto da incerteza do proprio lambda.
     */
    public static Map<String, Double> tempoComMaisGols(double lc, double lf) {
        double total = lc + lf;
        double l1 = total * FRACAO_1T;
        double l2 = total * (1 - FRACAO_1T);

        int max = 15;
        double[] p1 = new double[max + 1];
        double[] p2 = new double[max + 1];
        for (int k = 0; k <= max; k++) {
            p1[k] = MatrizPlacares.poisson(k, l1);
            p2[k] = MatrizPlacares.poisson(k, l2);
        }

        double pPrimeiro = 0, pEmpate = 0, pSegundo = 0;
        for (int a = 0; a <= max; a++) {
            for (int b = 0; b <= max; b++) {
                double p = p1[a] * p2[b];
                if (a > b) pPrimeiro += p;
                else if (a == b) pEmpate += p;
                else pSegundo += p;
            }
        }
        double soma = pPrimeiro + pEmpate + pSegundo;
        if (soma <= 0) soma = 1;

        Map<String, Double> out = new LinkedHashMap<>();
        out.put("PRIMEIRO_TEMPO", pPrimeiro / soma);
        out.put("EMPATE", pEmpate / soma);
        out.put("SEGUNDO_TEMPO", pSegundo / soma);
        return out;
    }
}