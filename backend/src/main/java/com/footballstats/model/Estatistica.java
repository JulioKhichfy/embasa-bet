package com.footballstats.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ESTATISTICA (1..1 com PARTIDA).
 * Guarda os valores de cada item para o clube da CASA e o clube de FORA.
 *
 * Em vez de ~90 colunas fixas, usamos dois mapas <campo, valor>. As chaves sao
 * exatamente os 'campo' definidos em StatFields.CAMPOS, o que mantem entidade,
 * parser e frontend sincronizados por um unico ponto de verdade.
 */
@Entity
@Table(name = "estatistica")
@Getter @Setter @NoArgsConstructor
public class Estatistica {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partida_id", nullable = false)
    @JsonIgnore
    private Partida partida;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "estatistica_casa", joinColumns = @JoinColumn(name = "estatistica_id"))
    @MapKeyColumn(name = "campo")
    @Column(name = "valor")
    private Map<String, Float> casa = new HashMap<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "estatistica_fora", joinColumns = @JoinColumn(name = "estatistica_id"))
    @MapKeyColumn(name = "campo")
    @Column(name = "valor")
    private Map<String, Float> fora = new HashMap<>();

    /** Garante 0.0 em todos os campos ausentes, seguindo a metadata. */
    public void preencherZerosFaltantes() {
        for (StatFields.StatMeta m : StatFields.CAMPOS) {
            casa.putIfAbsent(m.getCampo(), 0f);
            fora.putIfAbsent(m.getCampo(), 0f);
        }
    }

    /** Mapas ordenados pela metadata (util para respostas previsiveis). */
    @Transient
    @JsonIgnore
    public Map<String, Float> getCasaOrdenado() {
        Map<String, Float> out = new LinkedHashMap<>();
        for (StatFields.StatMeta m : StatFields.CAMPOS) out.put(m.getCampo(), casa.getOrDefault(m.getCampo(), 0f));
        return out;
    }

    @Transient
    @JsonIgnore
    public Map<String, Float> getForaOrdenado() {
        Map<String, Float> out = new LinkedHashMap<>();
        for (StatFields.StatMeta m : StatFields.CAMPOS) out.put(m.getCampo(), fora.getOrDefault(m.getCampo(), 0f));
        return out;
    }
}
