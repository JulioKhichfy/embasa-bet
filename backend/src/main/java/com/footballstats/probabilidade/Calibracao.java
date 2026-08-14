package com.footballstats.probabilidade;

import java.util.ArrayList;
import java.util.List;

/**
 * METRICAS DE CALIBRACAO.
 *
 * Um modelo pode estar ERRADO de duas formas independentes, e confundi-las e o
 * jeito mais rapido de perder dinheiro achando que se tem vantagem:
 *
 *   DISCRIMINACAO -- o modelo separa os casos? Dizer 50% para tudo e
 *   perfeitamente calibrado e completamente inutil.
 *
 *   CALIBRACAO -- quando ele diz 60%, acontece 60% das vezes? Um modelo pode
 *   ordenar os jogos muito bem e ainda assim inflar todas as probabilidades.
 *
 * Para apostar, CALIBRACAO e o que manda. O EV e calculado direto sobre p: se o
 * modelo diz 60% onde a verdade e 45%, todo EV positivo que ele apontar e
 * ficcao aritmetica. Um modelo mal calibrado nao perde devagar -- ele aponta
 * exatamente para as apostas erradas, porque o "valor" que ele enxerga e o
 * proprio erro dele.
 *
 * ATENCAO AO SINAL: Brier baixo NAO significa modelo bom. Um evento raro (5% de
 * base) recebe Brier ~0,0475 so chutando a taxa base. Por isso o numero que
 * importa e o BRIER SKILL SCORE, que compara contra esse chute burro.
 */
public final class Calibracao {

    /** Uma previsao e o que aconteceu. */
    public record Ponto(double p, boolean ocorreu) { }

    /** Uma faixa da curva de confiabilidade. */
    public record Faixa(double de, double ate, int n, double pMedia, double frequenciaReal) {
        /** Quanto o modelo errou nesta faixa, em pontos percentuais. */
        public double desvio() { return pMedia - frequenciaReal; }
    }

    public record Resultado(
            int n,
            double taxaBase,
            double brier,
            double brierBaseline,
            double brierSkillScore,
            double logLoss,
            double ece,
            /**
             * ECE que um modelo PERFEITAMENTE calibrado produziria nesta amostra,
             * so por flutuacao amostral. Ver {@link #eceRuido}.
             */
            double eceRuido,
            double vies,
            List<Faixa> faixas) {

        /**
         * Quantas vezes o erro de calibracao supera o ruido esperado.
         * Perto de 1 = indistinguivel de um modelo perfeito nesta amostra.
         */
        public double eceRelativo() {
            return eceRuido > 0 ? ece / eceRuido : 0;
        }

        /**
         * Leitura rapida para quem vai decidir se aposta ou nao.
         *
         * O limiar e RELATIVO ao ruido, nao absoluto. Com 300 casos espalhados
         * em 10 faixas, cada faixa tem umas 30 observacoes e a frequencia
         * observada varia varios pontos percentuais so por sorteio -- um ECE de
         * 5 pontos ali pode ser um modelo impecavel. Um limiar fixo condenaria
         * modelo bom por falta de dados, que e o erro oposto e igualmente caro:
         * voce jogaria fora a unica coisa que funciona.
         */
        public String veredito() {
            if (n < 100) return "AMOSTRA INSUFICIENTE (" + n + " casos; queira 300+)";
            if (brierSkillScore <= 0) return "INUTIL: não bate o chute na taxa base";
            double rel = eceRelativo();
            if (rel > 3.0) return "MAL CALIBRADO: não use para EV";
            if (rel > 2.0) return "SUSPEITO: desvio acima do ruído; investigue";
            if (n < 300)   return "COMPATÍVEL COM CALIBRADO (amostra ainda curta)";
            return "CALIBRADO";
        }
    }

    private Calibracao() { }

    /** Numero de faixas da curva de confiabilidade. */
    private static final int FAIXAS = 10;

    public static Resultado avaliar(List<Ponto> pontos) {
        int n = pontos.size();
        if (n == 0) return new Resultado(0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());

        int ocorridos = 0;
        for (Ponto p : pontos) if (p.ocorreu()) ocorridos++;
        double taxaBase = (double) ocorridos / n;

        double brier = 0, logLoss = 0, somaP = 0;
        for (Ponto pt : pontos) {
            double y = pt.ocorreu() ? 1 : 0;
            double p = Math.min(1 - 1e-15, Math.max(1e-15, pt.p()));
            brier += (p - y) * (p - y);
            logLoss += -(y * Math.log(p) + (1 - y) * Math.log(1 - p));
            somaP += pt.p();
        }
        brier /= n;
        logLoss /= n;

        // Baseline: prever sempre a taxa base observada ("climatologia").
        double brierBaseline = taxaBase * (1 - taxaBase);
        double bss = brierBaseline > 0 ? 1 - brier / brierBaseline : 0;

        // Viés global: positivo = o modelo é otimista demais no agregado.
        double vies = somaP / n - taxaBase;

        // ---- curva de confiabilidade ----
        int[] cont = new int[FAIXAS];
        int[] acertos = new int[FAIXAS];
        double[] somaFaixa = new double[FAIXAS];
        for (Ponto pt : pontos) {
            int b = (int) Math.floor(pt.p() * FAIXAS);
            if (b >= FAIXAS) b = FAIXAS - 1;
            if (b < 0) b = 0;
            cont[b]++;
            somaFaixa[b] += pt.p();
            if (pt.ocorreu()) acertos[b]++;
        }

        List<Faixa> faixas = new ArrayList<>();
        double ece = 0, ruido = 0;
        for (int b = 0; b < FAIXAS; b++) {
            if (cont[b] == 0) continue;
            double pMedia = somaFaixa[b] / cont[b];
            double freq = (double) acertos[b] / cont[b];
            faixas.add(new Faixa(b / (double) FAIXAS, (b + 1) / (double) FAIXAS,
                    cont[b], pMedia, freq));
            double peso = cont[b] / (double) n;
            ece += peso * Math.abs(pMedia - freq);
            ruido += peso * desvioEsperado(pMedia, cont[b]);
        }

        return new Resultado(n, taxaBase, brier, brierBaseline, bss, logLoss, ece, ruido, vies, faixas);
    }

    /** Intervalo de confianca do BSS por bootstrap pareado. */
    public record IntervaloBSS(double inferior, double mediana, double superior,
                               double probabilidadePositivo) {
        /** O intervalo inclui zero? Se sim, nao ha skill DETECTAVEL nesta amostra. */
        public boolean incluiZero() { return inferior <= 0 && superior >= 0; }

        public String leitura() {
            if (superior < 0) return "PIOR QUE A TAXA BASE (com confiança)";
            if (inferior > 0) return "MELHOR QUE A TAXA BASE (com confiança)";
            return "INDISTINGUÍVEL DA TAXA BASE nesta amostra";
        }
    }

    /**
     * Intervalo de 95% para o Brier Skill Score, por bootstrap.
     *
     * POR QUE ISTO E NECESSARIO
     * -------------------------
     * Um BSS pontual de -0,06 em 86 casos nao distingue "o modelo e pior que
     * chutar a taxa base" de "o modelo empata com a taxa base e a amostra e
     * curta". As duas conclusoes levam a acoes MUITO diferentes: a primeira diz
     * para jogar o modelo fora, a segunda diz para coletar mais dados.
     *
     * Reamostramos as partidas COM reposicao e recalculamos BSS em cada
     * reamostra. Pareado por construcao -- a mesma reamostra alimenta modelo e
     * baseline -- o que remove a variacao comum e deixa so a diferenca, que e o
     * que interessa.
     */
    public static IntervaloBSS bootstrapBSS(List<Ponto> pontos, int reps, long semente) {
        int n = pontos.size();
        if (n < 10) return new IntervaloBSS(0, 0, 0, 0);

        java.util.Random rnd = new java.util.Random(semente);
        double[] amostras = new double[reps];
        int positivos = 0;

        for (int r = 0; r < reps; r++) {
            double somaBrier = 0;
            int ocorridos = 0;
            double[] ps = new double[n];
            boolean[] ys = new boolean[n];
            for (int i = 0; i < n; i++) {
                Ponto pt = pontos.get(rnd.nextInt(n));
                ps[i] = pt.p();
                ys[i] = pt.ocorreu();
                if (pt.ocorreu()) ocorridos++;
            }
            double taxa = (double) ocorridos / n;
            for (int i = 0; i < n; i++) {
                double y = ys[i] ? 1 : 0;
                somaBrier += (ps[i] - y) * (ps[i] - y);
            }
            double brier = somaBrier / n;
            double base = taxa * (1 - taxa);
            double bss = base > 0 ? 1 - brier / base : 0;
            amostras[r] = bss;
            if (bss > 0) positivos++;
        }
        java.util.Arrays.sort(amostras);
        return new IntervaloBSS(
                amostras[(int) (reps * 0.025)],
                amostras[reps / 2],
                amostras[(int) (reps * 0.975)],
                (double) positivos / reps);
    }

    public static IntervaloBSS bootstrapBSS(List<Ponto> pontos) {
        return bootstrapBSS(pontos, 2000, 42);
    }

    /**
     * Desvio ABSOLUTO esperado entre frequencia observada e probabilidade real
     * numa faixa com k observacoes, mesmo sob calibracao perfeita.
     *
     * A frequencia observada e uma media de k Bernoullis: desvio-padrao
     * sqrt(p(1-p)/k). Para uma normal, E|X - mu| = sigma * sqrt(2/pi) ~ 0.7979
     * sigma. Somado com peso por faixa, da o piso de ruido do ECE.
     *
     * Sem isto, o ECE de uma amostra pequena parece erro de modelo quando e so
     * falta de dados -- e a conclusao errada e cara nas duas direcoes.
     */
    static double desvioEsperado(double p, int k) {
        if (k <= 0) return 0;
        double v = Math.max(1e-9, p * (1 - p));
        return Math.sqrt(v / k) * Math.sqrt(2 / Math.PI);
    }

    /** Curva de confiabilidade em texto, para log e relatório. */
    public static String curva(Resultado r) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  %-14s %6s %9s %9s %9s%n",
                "faixa", "n", "previsto", "real", "desvio"));
        for (Faixa f : r.faixas()) {
            sb.append(String.format("  %4.0f%%-%3.0f%%      %6d %8.1f%% %8.1f%% %+8.1f pp%n",
                    f.de() * 100, f.ate() * 100, f.n(),
                    f.pMedia() * 100, f.frequenciaReal() * 100, f.desvio() * 100));
        }
        return sb.toString();
    }
}