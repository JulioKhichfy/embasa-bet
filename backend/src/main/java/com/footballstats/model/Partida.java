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

    @OneToOne(mappedBy = "partida", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Estatistica estatistica;

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
}
