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

    /**
     * Id do torneio na fonte: /football/tournament/brazil/brasileirao-serie-a/325
     * -> 325.
     *
     * Resolve um problema concreto e recorrente: o nome do campeonato brasileiro
     * carrega o patrocinador e muda de temporada em temporada ("Brasileirão
     * Assaí" -> "Brasileirão Betano"). Casar por nome criaria um campeonato novo
     * a cada troca de patrocinio, quebrando o historico em dois. O id 325
     * atravessa tudo isso.
     */
    @Column(unique = true)
    private Long idExterno;

    // 1 NACAO possui 0..N CAMPEONATO
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "nacao_id", nullable = false)
    private Nacao nacao;

    // 1 CAMPEONATO possui 0..N CLUBE
    @OneToMany(mappedBy = "campeonato", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<Clube> clubes = new ArrayList<>();
}