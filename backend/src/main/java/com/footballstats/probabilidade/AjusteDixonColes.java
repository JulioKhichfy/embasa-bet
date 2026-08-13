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
                         double logVerossimilhanca, int iteracoes, boolean convergiu) {

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

    private AjusteDixonColes() { }

    /** ln P(x,y) sob Dixon-Coles para uma partida. */
    static double lnProbDC(int x, int y, double lambda, double mu, double rho) {
        double t = MatrizPlacares.tau(x, y, lambda, mu, rho);
        if (t <= 0) return Double.NEGATIVE_INFINITY;
        double lnPoisX = -lambda + x * Math.log(lambda) - MatrizPlacares.lnFat(x);
        double lnPoisY = -mu + y * Math.log(mu) - MatrizPlacares.lnFat(y);
        return lnPoisX + lnPoisY + Math.log(t);
    }

    static double logLL(List<PartidaBruta> parts, double[] ataque, double[] defesa,
                        double mando, double rho) {
        double s = 0;
        for (PartidaBruta p : parts) {
            double lambda = Math.exp(ataque[p.casa()] + defesa[p.fora()] + mando);
            double mu     = Math.exp(ataque[p.fora()] + defesa[p.casa()]);
            double l = lnProbDC(p.golsCasa(), p.golsFora(), lambda, mu, rho);
            if (l == Double.NEGATIVE_INFINITY) return Double.NEGATIVE_INFINITY;
            s += l;
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

    public static Ajuste estimar(int nTimes, List<PartidaBruta> partidas) {
        return estimar(nTimes, partidas, 60);
    }

    public static Ajuste estimar(int nTimes, List<PartidaBruta> partidas, int maxIter) {
        double[] ataque = new double[nTimes];
        double[] defesa = new double[nTimes];
        double mando = 0.1;
        double rho = MatrizPlacares.Params.padrao().rho();

        if (partidas.isEmpty() || nTimes == 0) {
            return new Ajuste(List.of(), mando, rho, 0, 0, false);
        }

        double llAnt = logLL(partidas, ataque, defesa, mando, rho);
        int it = 0;
        boolean convergiu = false;

        for (; it < maxIter; it++) {
            final double mandoIter = mando;
            final double rhoIter = rho;
            for (int i = 0; i < nTimes; i++) {
                final int idx = i;
                ataque[i] = maximiza1D(v -> {
                    double bak = ataque[idx]; ataque[idx] = v;
                    double l = logLL(partidas, ataque, defesa, mandoIter, rhoIter);
                    ataque[idx] = bak; return l;
                }, -3, 3, 40);
                defesa[i] = maximiza1D(v -> {
                    double bak = defesa[idx]; defesa[idx] = v;
                    double l = logLL(partidas, ataque, defesa, mandoIter, rhoIter);
                    defesa[idx] = bak; return l;
                }, -3, 3, 40);
            }
            // centraliza o ataque: fixa a escala (ver nota de identificabilidade)
            double media = 0;
            for (double v : ataque) media += v;
            media /= nTimes;
            for (int i = 0; i < nTimes; i++) ataque[i] -= media;

            final double rhoAtual = rho;
            mando = maximiza1D(v -> logLL(partidas, ataque, defesa, v, rhoAtual), -1, 1, 40);
            final double mandoAtual = mando;
            rho = maximiza1D(v -> logLL(partidas, ataque, defesa, mandoAtual, v), -0.30, 0.10, 40);

            double ll = logLL(partidas, ataque, defesa, mando, rho);
            if (Math.abs(ll - llAnt) < 1e-5) { convergiu = true; llAnt = ll; it++; break; }
            llAnt = ll;
        }

        List<ForcaClube> forcas = new ArrayList<>(nTimes);
        for (int i = 0; i < nTimes; i++) forcas.add(new ForcaClube(i, ataque[i], defesa[i]));
        return new Ajuste(forcas, mando, rho, llAnt, it, convergiu);
    }
}