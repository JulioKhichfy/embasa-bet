package com.footballstats.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "campeonato")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Campeonato {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nome;

    // 1 NACAO possui 0..N CAMPEONATO
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "nacao_id", nullable = false)
    private Nacao nacao;

    // 1 CAMPEONATO possui 0..N CLUBE
    @OneToMany(mappedBy = "campeonato", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<Clube> clubes = new ArrayList<>();
}
