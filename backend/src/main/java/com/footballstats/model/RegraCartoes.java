package com.footballstats.model;

import lombok.*;

/**
 * REGRA DE CONTAGEM DE CARTOES.
 *
 * O SofaScore fornece cartoesAmarelos e cartoesVermelhos SEPARADOS. Os mercados
 * de "total de cartoes" das casas de aposta trabalham com UM numero so, e a
 * conversao nao e obvia:
 *
 *   - Muitas casas contam VERMELHO = 2 cartoes (nao 1).
 *   - O jogador expulso por 2 amarelos pode contar 1, 2 ou 3 dependendo da regra.
 *   - Nao esta claro se o SofaScore ja soma o 2o amarelo dentro de
 *     cartoesVermelhos (verificavel empiricamente: procure jogos com expulsao
 *     por 2 amarelos e confira se amarelos+vermelhos bate com a sumula).
 *
 * Se a nossa contagem divergir da regra de liquidacao da casa, o lambda estimado
 * fica com VIES SISTEMATICO. Como vermelho e raro (~0,25/jogo), o vies e pequeno
 * e NAO aparece em teste rapido -- ele corroi devagar exatamente as linhas de
 * fronteira (mais de 3, mais de 4). Por isso a regra e parametro, nao constante:
 * ajuste em application.properties e recalibre sem tocar em codigo.
 *
 * Configuracao (application.properties):
 *   cartoes.peso.amarelo=1.0
 *   cartoes.peso.vermelho=2.0
 *   cartoes.segundo-amarelo-ja-somado=true
 */
@Getter @AllArgsConstructor
public class RegraCartoes {

    /** Quanto vale um cartao amarelo na contagem da casa. */
    private final float pesoAmarelo;

    /** Quanto vale um cartao vermelho na contagem da casa. */
    private final float pesoVermelho;

    /**
     * true  -> assumimos que o amarelo que virou vermelho JA esta contado dentro
     *          de cartoesAmarelos (nada a corrigir).
     * false -> o 2o amarelo nao aparece em cartoesAmarelos; somamos um amarelo
     *          extra por vermelho para aproximar a contagem da casa.
     *
     * ATENCAO: isto e uma aproximacao. Nao sabemos, por vermelho, se foi direto
     * ou por acumulo. Ver {@link #incertezaPorVermelho()}.
     */
    private final boolean segundoAmareloJaSomado;

    /** Convencao mais comum no mercado. Confirme contra a sua casa antes de apostar. */
    public static RegraCartoes padrao() {
        return new RegraCartoes(1.0f, 2.0f, true);
    }

    /** Contagem "ingenua": cada cartao vale 1. Util para comparar calibracoes. */
    public static RegraCartoes simples() {
        return new RegraCartoes(1.0f, 1.0f, true);
    }

    /**
     * Converte o par (amarelos, vermelhos) do SofaScore no total de cartoes
     * segundo a regra da casa.
     */
    public float total(float amarelos, float vermelhos) {
        float extra = segundoAmareloJaSomado ? 0f : vermelhos * pesoAmarelo;
        return amarelos * pesoAmarelo + vermelhos * pesoVermelho + extra;
    }

    /** Total combinado dos dois lados de uma partida. */
    public float totalPartida(float amarelosCasa, float vermelhosCasa,
                              float amarelosFora, float vermelhosFora) {
        return total(amarelosCasa, vermelhosCasa) + total(amarelosFora, vermelhosFora);
    }

    /**
     * Margem de erro por cartao vermelho, em "cartoes".
     *
     * Um vermelho direto e um vermelho por acumulo podem valer numeros diferentes
     * na casa, e o dado bruto nao distingue os dois. Esta e a maior divergencia
     * possivel entre a nossa contagem e a real, POR vermelho. O motor usa isso
     * para alargar o intervalo de confianca do mercado de cartoes -- e, quando o
     * EV cabe dentro dessa margem, a aposta nao deve entrar no bilhete.
     */
    public float incertezaPorVermelho() {
        return pesoAmarelo;
    }
}
