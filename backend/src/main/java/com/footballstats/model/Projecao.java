package com.footballstats.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * PROJECAO: a nossa probabilidade para uma selecao de um mercado, num momento.
 *
 * POR QUE PERSISTIR E NAO CALCULAR NA HORA
 * ----------------------------------------
 * Sem isto nao existe backtest. Para medir se o modelo e calibrado (das vezes
 * que ele disse 30%, aconteceu ~30%?) e preciso ter guardado o que ele disse
 * ANTES do jogo. Recalcular depois usaria dados que incluem o proprio jogo e
 * daria uma nota inflada -- o erro classico de avaliar modelo com vazamento.
 *
 * Guardamos tambem lambdaCasa/lambdaFora, o modelo e a versao do ajuste: quando
 * uma projecao velha parecer estranha, da para reconstruir exatamente de onde
 * ela veio.
 */
@Entity
@Table(name = "projecao", indexes = {
        @Index(name = "ix_proj_confronto", columnList = "confronto_id"),
        @Index(name = "ix_proj_mercado",   columnList = "mercado")
})
@Getter @Setter @NoArgsConstructor
public class Projecao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confronto_id", nullable = false)
    @JsonIgnore
    private Confronto confronto;

    @Column(nullable = false)
    private String mercado;

    private Double linha;
    private Double linhaAte;

    @Column(nullable = false)
    private String selecao;

    /** Probabilidade em [0,1]. */
    @Column(nullable = false)
    private Double probabilidade;

    /** Modelo usado: POISSON, DIXON_COLES, BIVARIATE, NEG_BIN. */
    @Column(nullable = false)
    private String modelo;

    private Double lambdaCasa;
    private Double lambdaFora;

    /** Quantas partidas alimentaram o ajuste; amostra pequena = projeção frágil. */
    private Integer amostra;

    @Column(nullable = false)
    private Instant geradoEm = Instant.now();
}