package com.footballstats.model;

import lombok.*;

import java.util.List;
import java.util.Optional;

/**
 * REGISTRO CENTRAL DE MERCADOS.
 *
 * Mesmo padrao de StatFields: um unico array e a fonte da verdade que dirige
 * (1) qual distribuicao o motor usa, (2) qual campo do StatFields alimenta o
 * modelo, (3) quais linhas existem, (4) como o frontend rotula tudo.
 *
 * Acrescentar um mercado = acrescentar uma linha aqui. Nenhum switch espalhado
 * pelo codigo precisa saber que ele existe.
 *
 * ESCOLHA DA DISTRIBUICAO -- e onde mora a modelagem de verdade:
 *
 *   MATRIZ_PLACAR   Tudo que deriva de (golsCasa, golsFora) sai por soma sobre a
 *                   matriz conjunta que o Dixon-Coles ja produz. Zero matematica
 *                   nova: ambos marcam, over, faixa e margem sao recortes
 *                   diferentes da MESMA matriz. Bonus importante: como a matriz e
 *                   conjunta, multiplas correlacionadas do mesmo jogo (over 2.5 +
 *                   ambos marcam) saem certas, sem multiplicar probabilidades.
 *
 *   NEG_BINOMIAL    Escanteios e cartoes sao SUPERDISPERSOS (variancia > media).
 *                   Poisson subestima as caudas e faz o over das linhas altas
 *                   parecer barato quando nao esta. r por metodo dos momentos:
 *                   r = mu^2 / (sigma^2 - mu).
 *
 *   POISSON         Chutes e chutes ao gol tem dispersao proxima de Poisson.
 *
 *   BINOMIAL_COND   Defesas do goleiro NAO sao um processo independente: sao
 *                   uma fracao dos chutes ao gol do adversario. Modelar como
 *                   Binomial(chutesAoGol_adversario, p_defesa) acopla o mercado
 *                   ao que de fato o gera e calibra muito melhor que um Poisson
 *                   solto.
 *
 *   COMPARATIVA     P(X_casa > X_fora), P(empate), P(X_fora > X_casa) por
 *                   convolucao. CUIDADO: as duas contagens NAO sao independentes
 *                   -- escanteios e chutes sao anticorrelacionados entre os times
 *                   (quem tem posse produz mais). Sem fator de correlacao
 *                   empirico, os extremos ficam superestimados.
 */
public class Mercados {

    /** Familia governa qual estimador de lambda o motor invoca. */
    public enum Familia { GOLS, CONTAGEM, COMPARATIVO }

    /** Forma da distribuicao usada para transformar lambda em probabilidade. */
    public enum Distribuicao { MATRIZ_PLACAR, POISSON, NEG_BINOMIAL, BINOMIAL_COND, COMPARATIVA }

    /** Formato das selecoes oferecidas. */
    public enum TipoLinha {
        /** Sim / Nao, sem linha numerica. */
        BINARIO,
        /** Mais de N. A linha e o piso exclusivo: P(X > N). */
        OVER,
        /** Entre 1 e N inclusive: P(1 <= X <= N). O zero NAO conta. */
        INTERVALO,
        /** Tres vias: casa / empate / fora, ou 1T / igual / 2T. */
        TRIPLO
    }

    /**
     * Sentinela para o mercado de cartoes: nao existe campo "cartoes" no
     * StatFields, o valor e derivado de cartoesAmarelos + cartoesVermelhos via
     * {@link RegraCartoes}.
     */
    public static final String CAMPO_CARTOES = "@cartoes";

    /** Sentinela para mercados que derivam do placar, nao de um campo de stat. */
    public static final String CAMPO_PLACAR = "@placar";

    @Getter @AllArgsConstructor
    public static class MercadoMeta {
        /** identificador estavel; usado em Odd, Projecao e na API */
        private final String codigo;
        /** rotulo exibido na UI, em pt-BR */
        private final String rotulo;
        private final Familia familia;
        private final Estatistica.Periodo periodo;
        private final TipoLinha tipoLinha;
        private final Distribuicao distribuicao;
        /** campo do StatFields que alimenta o modelo, ou uma das sentinelas */
        private final String campoStat;
        /** linhas padrao; vazio para BINARIO e TRIPLO */
        private final List<Double> linhas;

        /** Este mercado precisa de dados por tempo para ser estimavel? */
        public boolean exigePeriodo() {
            return periodo != Estatistica.Periodo.PARTIDA;
        }
    }

    public static final List<MercadoMeta> MERCADOS = List.of(

        // ---------------- Ambos os times marcarem ----------------
        new MercadoMeta("btts", "Ambos marcam", Familia.GOLS,
                Estatistica.Periodo.PARTIDA, TipoLinha.BINARIO,
                Distribuicao.MATRIZ_PLACAR, CAMPO_PLACAR, List.of()),

        new MercadoMeta("btts_1t", "Ambos marcam - 1º tempo", Familia.GOLS,
                Estatistica.Periodo.PRIMEIRO_TEMPO, TipoLinha.BINARIO,
                Distribuicao.MATRIZ_PLACAR, CAMPO_PLACAR, List.of()),

        new MercadoMeta("btts_2t", "Ambos marcam - 2º tempo", Familia.GOLS,
                Estatistica.Periodo.SEGUNDO_TEMPO, TipoLinha.BINARIO,
                Distribuicao.MATRIZ_PLACAR, CAMPO_PLACAR, List.of()),

        // ---------------- Total de gols ----------------
        // "Mais de 2" = P(total > 2) = P(total >= 3), identico a Over 2.5 --
        // EXCETO se a casa devolver o dinheiro no total exato. Confirme a regra.
        new MercadoMeta("total_gols", "Total de gols", Familia.GOLS,
                Estatistica.Periodo.PARTIDA, TipoLinha.OVER,
                Distribuicao.MATRIZ_PLACAR, CAMPO_PLACAR,
                List.of(0d, 1d, 2d, 3d, 4d, 5d)),

        // ---------------- Faixa de gols ----------------
        // Piso fixo em 1: 0-0 perde em TODAS as faixas. E a pegadinha do mercado.
        new MercadoMeta("faixa_gols", "Faixa de gols", Familia.GOLS,
                Estatistica.Periodo.PARTIDA, TipoLinha.INTERVALO,
                Distribuicao.MATRIZ_PLACAR, CAMPO_PLACAR,
                List.of(2d, 3d, 4d, 5d)),

        // ---------------- Margem de vitoria ----------------
        new MercadoMeta("margem_vitoria", "Margem de vitória", Familia.GOLS,
                Estatistica.Periodo.PARTIDA, TipoLinha.OVER,
                Distribuicao.MATRIZ_PLACAR, CAMPO_PLACAR,
                List.of(1d, 2d, 3d, 4d)),

        // ---------------- Tempo com mais gols ----------------
        new MercadoMeta("tempo_mais_gols", "Tempo com mais gols", Familia.GOLS,
                Estatistica.Periodo.PARTIDA, TipoLinha.TRIPLO,
                Distribuicao.MATRIZ_PLACAR, CAMPO_PLACAR, List.of()),

        // ---------------- Escanteios ----------------
        new MercadoMeta("escanteios", "Escanteios - combinados", Familia.CONTAGEM,
                Estatistica.Periodo.PARTIDA, TipoLinha.OVER,
                Distribuicao.NEG_BINOMIAL, "escanteios",
                List.of(4d, 5d, 6d, 7d, 8d)),

        // ---------------- Cartoes ----------------
        new MercadoMeta("cartoes", "Cartões - combinados", Familia.CONTAGEM,
                Estatistica.Periodo.PARTIDA, TipoLinha.OVER,
                Distribuicao.NEG_BINOMIAL, CAMPO_CARTOES,
                List.of(0d, 1d, 2d, 3d, 4d)),

        new MercadoMeta("ambos_cartao", "Ambos recebem cartão", Familia.CONTAGEM,
                Estatistica.Periodo.PARTIDA, TipoLinha.BINARIO,
                Distribuicao.NEG_BINOMIAL, CAMPO_CARTOES, List.of()),

        // ---------------- Chutes ----------------
        // Linhas padrao sao chute inicial. O ideal e sobrescrever com as linhas
        // que a casa realmente ofereceu no dia, vindas junto com as odds.
        new MercadoMeta("total_chutes", "Total de chutes", Familia.CONTAGEM,
                Estatistica.Periodo.PARTIDA, TipoLinha.OVER,
                Distribuicao.POISSON, "finalizacoes",
                List.of(20d, 22d, 24d, 26d, 28d)),

        new MercadoMeta("total_chutes_gol", "Total de chutes ao gol", Familia.CONTAGEM,
                Estatistica.Periodo.PARTIDA, TipoLinha.OVER,
                Distribuicao.POISSON, "finalizacoesNoGol",
                List.of(6d, 7d, 8d, 9d, 10d)),

        // ---------------- Defesas do goleiro ----------------
        new MercadoMeta("defesas", "Defesas dos goleiros", Familia.CONTAGEM,
                Estatistica.Periodo.PARTIDA, TipoLinha.OVER,
                Distribuicao.BINOMIAL_COND, "defesasDoGoleiro",
                List.of(0d, 1d, 2d, 3d, 4d, 5d)),

        // ---------------- Time com maior numero de ----------------
        new MercadoMeta("mais_chutes", "Time com mais chutes", Familia.COMPARATIVO,
                Estatistica.Periodo.PARTIDA, TipoLinha.TRIPLO,
                Distribuicao.COMPARATIVA, "finalizacoes", List.of()),

        new MercadoMeta("mais_chutes_gol", "Time com mais chutes ao gol", Familia.COMPARATIVO,
                Estatistica.Periodo.PARTIDA, TipoLinha.TRIPLO,
                Distribuicao.COMPARATIVA, "finalizacoesNoGol", List.of()),

        new MercadoMeta("mais_escanteios", "Time com mais escanteios", Familia.COMPARATIVO,
                Estatistica.Periodo.PARTIDA, TipoLinha.TRIPLO,
                Distribuicao.COMPARATIVA, "escanteios", List.of()),

        new MercadoMeta("mais_cartoes", "Time com mais cartões", Familia.COMPARATIVO,
                Estatistica.Periodo.PARTIDA, TipoLinha.TRIPLO,
                Distribuicao.COMPARATIVA, CAMPO_CARTOES, List.of())
    );

    public static Optional<MercadoMeta> porCodigo(String codigo) {
        return MERCADOS.stream().filter(m -> m.getCodigo().equals(codigo)).findFirst();
    }

    /** Mercados que so funcionam com dados por tempo coletados. */
    public static List<MercadoMeta> dependentesDePeriodo() {
        return MERCADOS.stream().filter(MercadoMeta::exigePeriodo).toList();
    }

    private Mercados() { }
}
