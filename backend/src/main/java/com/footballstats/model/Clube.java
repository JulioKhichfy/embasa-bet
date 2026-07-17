package com.footballstats.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "clube")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Clube {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nome;

    // 1 CAMPEONATO possui 0..N CLUBE
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "campeonato_id", nullable = false)
    @JsonIgnoreProperties({"clubes"})
    private Campeonato campeonato;
}
