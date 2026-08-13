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

    /**
     * Id deste clube na fonte (SofaScore), lido do breadcrumb da pagina:
     * /football/team/botafogo/1958 -> 1958.
     *
     * E a identidade mais estavel que existe. O nome exibido muda (patrocinio,
     * grafia, acento, sufixo de estado) e e justamente o que gera clube
     * duplicado na importacao; o id nao muda. Quando presente, ele tem
     * precedencia sobre qualquer casamento por nome.
     *
     * Nullable porque clubes cadastrados a mao ou importados antes desta versao
     * nao tem. O ClubeResolver preenche assim que o clube aparece num import
     * que traga o id.
     */
    @Column(unique = true)
    private Long idExterno;

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