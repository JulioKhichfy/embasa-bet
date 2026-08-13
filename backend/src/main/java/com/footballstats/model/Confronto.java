package com.footballstats.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * CONFRONTO: um jogo que AINDA NAO ACONTECEU (ou acabou de acontecer).
 *
 * Por que nao reaproveitar Partida: Partida e um fato consumado -- tem placar e
 * estatistica, e a unique key (data, casa, fora) existe para impedir importar
 * duas vezes o mesmo resultado. Confronto e uma expectativa: tem hora de bola
 * rolando, arbitro escalado, odds que mudam ao longo do dia e nenhum placar.
 * Misturar os dois na mesma tabela obrigaria metade das colunas a serem nulas e
 * confundiria o motor sobre o que ja pode entrar na amostra de treino.
 *
 * Quando o jogo termina, o import normal do SofaScore cria a Partida; o
 * Confronto continua existindo como registro do que foi projetado e cotado --
 * e sem isso nao ha backtest honesto.
 */
@Entity
@Table(name = "confronto",
        uniqueConstraints = @UniqueConstraint(columnNames = {"data", "clube_casa_id", "clube_fora_id"}))
@Getter @Setter @NoArgsConstructor
public class Confronto {

    public enum Status { AGENDADO, EM_ANDAMENTO, ENCERRADO, CANCELADO }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate data;

    /** Hora do apito inicial; null quando a fonte nao informou. */
    private LocalTime hora;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "clube_casa_id", nullable = false)
    @JsonIgnoreProperties({"campeonato"})
    private Clube clubeCasa;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "clube_fora_id", nullable = false)
    @JsonIgnoreProperties({"campeonato"})
    private Clube clubeFora;

    private String arbitro;

    /**
     * Media historica de cartoes do arbitro, vinda do SofaScore.
     *
     * E o preditor isolado mais forte do mercado de cartoes -- costuma pesar
     * mais que a media somada dos dois clubes. Guardamos no Confronto, e nao
     * numa tabela de arbitros, porque o valor exibido e o vigente NA DATA: o
     * historico do arbitro muda com o tempo e queremos o numero que estava
     * disponivel quando a projecao foi feita.
     */
    private Float arbitroMediaAmarelos;
    private Float arbitroMediaVermelhos;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.AGENDADO;

    /** Id do evento na fonte (ex.: eventId do SofaScore), para reconciliar. */
    private String idExterno;

    @Transient
    public String descricao() {
        return (clubeCasa != null ? clubeCasa.getNome() : "?")
                + " x " + (clubeFora != null ? clubeFora.getNome() : "?")
                + " em " + data;
    }
}