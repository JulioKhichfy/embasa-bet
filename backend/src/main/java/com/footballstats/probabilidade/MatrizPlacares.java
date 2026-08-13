package com.footballstats.probabilidade;

/**
 * MATRIZ DE PLACARES -- porte do probabilidade.model.ts para o backend.
 *
 * POR QUE DUPLICAR O MOTOR
 * ------------------------
 * O modelo ja existe, validado, no frontend. Duplicar codigo numerico e
 * geralmente ma ideia -- as duas copias divergem em silencio. Mas a projecao
 * precisa ser PERSISTIDA junto com a foto das odds daquele momento, senao nao
 * existe backtest: nao da para medir calibracao de uma probabilidade que so
 * existiu na memoria do navegador e nunca foi gravada.
 *
 * O risco do porte foi tratado do unico jeito honesto: as duas implementacoes
 * sao rodadas sobre a mesma grade de entradas e comparadas celula a celula.
 * Enquanto os dois lados baterem na casa de 1e-12, a duplicacao e segura. Se um
 * dia divergirem, o teste acusa.
 *
 * O porte e deliberadamente LINHA A LINHA -- mesma ordem de operacoes, mesmas
 * constantes de Lanczos, mesmo lnFat por laco em vez de tabela. Qualquer
 * "melhoria" aqui quebraria a comparacao que garante a fidelidade.
 */
public final class MatrizPlacares {

    /** Gols maximos por lado. Truncar em 10 cobre mais de 99,99% da massa. */
    public static final int MAXG = 10;

    public enum Modelo { POISSON, DIXON_COLES, BIVARIATE, NEG_BIN }

    /** Parametros dos modelos, com os mesmos padroes do frontend. */
    public record Params(double rho, double lambda3, double r) {
        public static Params padrao() { return new Params(-0.05, 0.15, 8); }
        public Params comRho(double v)     { return new Params(v, lambda3, r); }
        public Params comLambda3(double v) { return new Params(rho, v, r); }
        public Params comR(double v)       { return new Params(rho, lambda3, v); }
    }

    private MatrizPlacares() { }

    // ------------------------------------------------------------------
    // Distribuicoes
    // ------------------------------------------------------------------

    static double lnFat(int k) {
        double s = 0;
        for (int i = 2; i <= k; i++) s += Math.log(i);
        return s;
    }

    /** P(X = k) para Poisson(lambda). */
    public static double poisson(int k, double lambda) {
        if (lambda <= 0) return k == 0 ? 1 : 0;
        return Math.exp(-lambda + k * Math.log(lambda) - lnFat(k));
    }

    private static final double[] LANCZOS = {
            0.99999999999980993, 676.5203681218851, -1259.1392167224028,
            771.32342877765313, -176.61502916214059, 12.507343278686905,
            -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7
    };

    /** ln(Gamma(x)) -- Lanczos, identico ao do frontend. */
    static double lnGamma(double x) {
        final int g = 7;
        if (x < 0.5) return Math.log(Math.PI / Math.sin(Math.PI * x)) - lnGamma(1 - x);
        x -= 1;
        double a = LANCZOS[0];
        double t = x + g + 0.5;
        for (int i = 1; i < g + 2; i++) a += LANCZOS[i] / (x + i);
        return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
    }

    /**
     * P(X = k) para Binomial Negativa com media lambda e dispersao r.
     * r -> infinito converge para Poisson; r menor = cauda mais pesada.
     */
    public static double negBin(int k, double lambda, double r) {
        if (lambda <= 0) return k == 0 ? 1 : 0;
        double p = r / (r + lambda);
        return Math.exp(lnGamma(k + r) - lnGamma(r) - lnFat(k)
                + r * Math.log(p) + k * Math.log(1 - p));
    }

    // ------------------------------------------------------------------
    // Construcao da matriz
    // ------------------------------------------------------------------

    private static double[][] matrizPoisson(double lc, double lf) {
        double[][] m = new double[MAXG + 1][MAXG + 1];
        for (int i = 0; i <= MAXG; i++)
            for (int j = 0; j <= MAXG; j++)
                m[i][j] = poisson(i, lc) * poisson(j, lf);
        return m;
    }

    /**
     * Correcao tau de Dixon-Coles nos placares baixos.
     * rho < 0 aumenta 0-0 e 1-1 e reduz 1-0 / 0-1 -- o padrao empirico do futebol.
     */
    static double tau(int i, int j, double lc, double lf, double rho) {
        if (i == 0 && j == 0) return 1 - lc * lf * rho;
        if (i == 0 && j == 1) return 1 + lc * rho;
        if (i == 1 && j == 0) return 1 + lf * rho;
        if (i == 1 && j == 1) return 1 - rho;
        return 1;
    }

    private static double[][] matrizDixonColes(double lc, double lf, double rho) {
        double[][] m = matrizPoisson(lc, lf);
        for (int i = 0; i <= 1; i++)
            for (int j = 0; j <= 1; j++)
                m[i][j] *= Math.max(0.0001, tau(i, j, lc, lf, rho));
        return m;
    }

    /**
     * Bivariate Poisson: X = X1 + X3, Y = X2 + X3, com X3 comum aos dois times.
     * l3 > 0 gera correlacao positiva (jogos abertos ou travados dos dois lados).
     */
    private static double[][] matrizBivariate(double lc, double lf, double l3) {
        double l1 = Math.max(0.01, lc - l3);
        double l2 = Math.max(0.01, lf - l3);
        double[][] m = new double[MAXG + 1][MAXG + 1];
        for (int i = 0; i <= MAXG; i++) {
            for (int j = 0; j <= MAXG; j++) {
                double s = 0;
                int kmax = Math.min(i, j);
                for (int k = 0; k <= kmax; k++)
                    s += poisson(i - k, l1) * poisson(j - k, l2) * poisson(k, l3);
                m[i][j] = s;
            }
        }
        return m;
    }

    private static double[][] matrizNegBin(double lc, double lf, double r) {
        double[][] m = new double[MAXG + 1][MAXG + 1];
        for (int i = 0; i <= MAXG; i++)
            for (int j = 0; j <= MAXG; j++)
                m[i][j] = negBin(i, lc, r) * negBin(j, lf, r);
        return m;
    }

    /** Matriz conforme o modelo, ainda NAO normalizada (igual ao frontend). */
    public static double[][] bruta(Modelo modelo, double lc, double lf, Params p) {
        return switch (modelo) {
            case DIXON_COLES -> matrizDixonColes(lc, lf, p.rho());
            case BIVARIATE   -> matrizBivariate(lc, lf, Math.min(p.lambda3(), Math.min(lc, lf) * 0.9));
            case NEG_BIN     -> matrizNegBin(lc, lf, p.r());
            default          -> matrizPoisson(lc, lf);
        };
    }

    /**
     * Matriz NORMALIZADA (soma 1).
     *
     * Sempre use esta para derivar mercado. A correcao tau e o truncamento em
     * MAXG fazem a matriz bruta somar um pouco diferente de 1; sem normalizar,
     * o erro entra direto na probabilidade e, como e pequeno e constante,
     * passaria despercebido enquanto corroi todo EV calculado em cima.
     */
    public static double[][] normalizada(Modelo modelo, double lc, double lf, Params p) {
        double[][] m = bruta(modelo, lc, lf, p);
        double soma = 0;
        for (int i = 0; i <= MAXG; i++)
            for (int j = 0; j <= MAXG; j++) soma += m[i][j];
        if (soma <= 0) soma = 1;
        for (int i = 0; i <= MAXG; i++)
            for (int j = 0; j <= MAXG; j++) m[i][j] /= soma;
        return m;
    }
}