package com.footballstats.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

/**
 * PARTIDA: contem os DOIS clubes (casa e fora), placar e a data.
 * 1 PARTIDA possui 0..1 ESTATISTICA.
 *
 * Constraint unica (data, clube_casa, clube_fora) evita importar a mesma
 * partida duas vezes.
 *
 * PLACAR POR TEMPO
 * ----------------
 * golsCasa1T/golsFora1T sao NULLABLE de proposito. Nem todo HTML do SofaScore
 * traz o intervalo de forma extraivel, e um 0 falso seria pior que a ausencia:
 * o motor precisa distinguir "0-0 no primeiro tempo" de "nao sabemos". Partidas
 * sem 1T simplesmente nao entram na amostra dos mercados por tempo.
 *
 * O 2o tempo NAO e armazenado: e sempre (final - 1T). Guardar seria duplicar
 * estado e abrir espaco para inconsistencia.
 */
@Entity
@Table(name = "partida",
       uniqueConstraints = @UniqueConstraint(columnNames = {"data", "clube_casa_id", "clube_fora_id"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Partida {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate data;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "clube_casa_id", nullable = false)
    @JsonIgnoreProperties({"campeonato"})
    private Clube clubeCasa;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "clube_fora_id", nullable = false)
    @JsonIgnoreProperties({"campeonato"})
    private Clube clubeFora;

    private Integer golsCasa = 0;
    private Integer golsFora = 0;

    /** Placar ao intervalo. NULL = nao extraido (nao confundir com 0). */
    private Integer golsCasa1T;
    private Integer golsFora1T;

    /**
     * Nome do arbitro, quando extraivel.
     *
     * E o preditor isolado mais forte do mercado de cartoes -- a variacao entre
     * arbitros costuma superar a variacao entre times. Sem ele, o modelo de
     * cartoes trabalha so com a media do campeonato e o intervalo fica largo.
     */
    private String arbitro;

    @OneToOne(mappedBy = "partida", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Estatistica estatistica;

    // ------------------------------------------------------------------
    // Derivados de placar
    // ------------------------------------------------------------------

    /** Pontos do clube da casa nesta partida (V=3, E=1, D=0). */
    @Transient
    public int pontosCasa() {
        if (golsCasa > golsFora) return 3;
        if (golsCasa.equals(golsFora)) return 1;
        return 0;
    }

    /** Pontos do clube de fora nesta partida. */
    @Transient
    public int pontosFora() {
        if (golsFora > golsCasa) return 3;
        if (golsFora.equals(golsCasa)) return 1;
        return 0;
    }

    /** Esta partida pode alimentar os mercados por tempo? */
    @Transient
    public boolean temPlacar1T() {
        return golsCasa1T != null && golsFora1T != null;
    }

    /** Gols do clube da casa no 2o tempo, ou null se o 1T nao e conhecido. */
    @Transient
    public Integer golsCasa2T() {
        return temPlacar1T() ? golsCasa - golsCasa1T : null;
    }

    /** Gols do clube de fora no 2o tempo, ou null se o 1T nao e conhecido. */
    @Transient
    public Integer golsFora2T() {
        return temPlacar1T() ? golsFora - golsFora1T : null;
    }

    /** Total de gols da partida. */
    @Transient
    public int totalGols() {
        return golsCasa + golsFora;
    }

    /** Total de gols no 1o tempo, ou null. */
    @Transient
    public Integer totalGols1T() {
        return temPlacar1T() ? golsCasa1T + golsFora1T : null;
    }

    /** Total de gols no 2o tempo, ou null. */
    @Transient
    public Integer totalGols2T() {
        return temPlacar1T() ? totalGols() - totalGols1T() : null;
    }

    /** Ambos os times marcaram na partida. */
    @Transient
    public boolean ambosMarcaram() {
        return golsCasa > 0 && golsFora > 0;
    }

    /** Ambos marcaram no 1o tempo. NULL se desconhecido. */
    @Transient
    public Boolean ambosMarcaram1T() {
        return temPlacar1T() ? (golsCasa1T > 0 && golsFora1T > 0) : null;
    }

    /** Ambos marcaram no 2o tempo. NULL se desconhecido. */
    @Transient
    public Boolean ambosMarcaram2T() {
        return temPlacar1T() ? (golsCasa2T() > 0 && golsFora2T() > 0) : null;
    }

    /**
     * Qual tempo teve mais gols: 1, 2, ou 0 para empate. NULL se desconhecido.
     * Mapeia direto no mercado "Tempo com mais gols" (que tem tres vias).
     */
    @Transient
    public Integer tempoComMaisGols() {
        if (!temPlacar1T()) return null;
        int t1 = totalGols1T(), t2 = totalGols2T();
        if (t1 > t2) return 1;
        if (t2 > t1) return 2;
        return 0;
    }

    /** Margem absoluta de vitoria (0 em empate). */
    @Transient
    public int margem() {
        return Math.abs(golsCasa - golsFora);
    }
}
