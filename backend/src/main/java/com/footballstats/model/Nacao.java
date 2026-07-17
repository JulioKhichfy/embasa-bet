package com.footballstats.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "nacao")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Nacao {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String nome;

    // 1 NACAO possui 0..N CAMPEONATO
    @OneToMany(mappedBy = "nacao", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<Campeonato> campeonatos = new ArrayList<>();
}
