package com.footballstats.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;

import java.util.LinkedHashSet;
import java.util.Set;

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

    /**
     * Apelidos manuais deste clube (ex.: "Galo", "Athletico-MG" para o
     * Atletico Mineiro). Cadastrados pelo usuario na tela de Cadastros e usados
     * na importacao para casar o nome vindo do SofaScore com o clube certo.
     *
     * EAGER + LinkedHashSet: sao poucos por clube e precisamos deles ao resolver
     * o clube na importacao; a ordem de insercao e preservada so por estetica.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "clube_apelido", joinColumns = @JoinColumn(name = "clube_id"))
    @Column(name = "apelido", nullable = false)
    private Set<String> apelidos = new LinkedHashSet<>();
}