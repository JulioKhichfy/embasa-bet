package com.footballstats.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * ODD: uma cotacao capturada, ja traduzida para a taxonomia de Mercados.
 *
 * NAO HA UNIQUE KEY, E ISSO E PROPOSITAL. Odds se movem durante o dia. Cada
 * captura e um fato novo com seu proprio carimbo de tempo, e a serie temporal
 * importa: o movimento de linha carrega informacao (dinheiro entrando, noticia
 * de escalacao) e e uma das poucas formas de saber se a nossa estimativa chegou
 * antes ou depois do mercado. Sobrescrever a cotacao anterior jogaria fora
 * justamente o sinal mais valioso.
 *
 * Os campos origem* guardam o texto cru da bet365. Custam pouco e permitem
 * REMAPEAR capturas antigas quando o MapeamentoMercado melhorar, sem precisar
 * recapturar nada -- mesma logica de guardar o HTML bruto do SofaScore.
 */
@Entity
@Table(name = "odd", indexes = {
        @Index(name = "ix_odd_confronto", columnList = "confronto_id"),
        @Index(name = "ix_odd_mercado",   columnList = "mercado")
})
@Getter @Setter @NoArgsConstructor
public class Odd {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confronto_id", nullable = false)
    @JsonIgnore
    private Confronto confronto;

    /** Codigo em Mercados.MERCADOS, ex. "escanteios". */
    @Column(nullable = false)
    private String mercado;

    /** Limiar. Em mercados OVER e sempre PISO EXCLUSIVO: a odd paga se X > linha. */
    private Double linha;

    /** Limite superior inclusivo em mercados de faixa (1-2 gols); senao null. */
    private Double linhaAte;

    @Column(nullable = false)
    private String selecao;

    /** Odd decimal como ofertada. */
    @Column(nullable = false)
    private Double valor;

    @Column(nullable = false)
    private Instant capturadoEm = Instant.now();

    // ---- texto cru da casa, para auditoria e remapeamento ----
    private String origemMercado;
    private String origemLinha;
    private String origemSelecao;

    /**
     * Probabilidade implicita BRUTA (1/odd).
     *
     * Nao e a probabilidade justa: a soma das implicitas de um mercado passa de
     * 100% pela margem da casa. Comparar a nossa estimativa contra este numero
     * direto faz toda aposta parecer ruim. A remocao da margem acontece no
     * calculo de valor, sobre o conjunto completo de selecoes do mercado.
     */
    @Transient
    public double probabilidadeImplicita() {
        return valor != null && valor > 0 ? 1.0 / valor : 0.0;
    }
}