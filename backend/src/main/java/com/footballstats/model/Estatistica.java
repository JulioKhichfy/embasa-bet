package com.footballstats.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ESTATISTICA (1..1 com PARTIDA).
 *
 * Guarda os valores de cada item para o clube da CASA e o clube de FORA, agora
 * em TRES PERIODOS.
 *
 * POR QUE POR PERIODO
 * -------------------
 * O SofaScore renderiza as estatisticas em paineis separados por periodo
 * (#tabpanel-ALL, #tabpanel-1ST, #tabpanel-2ND). Capturar os tres da acesso nao
 * so ao placar do intervalo, mas a escanteios, cartoes e chutes POR TEMPO --
 * base para mercados que a versao anterior do modelo nao conseguia atender.
 *
 * DIFERENCA DE TRATAMENTO ENTRE ALL E 1ST/2ND
 * -------------------------------------------
 * casa/fora (ALL) recebem preencherZerosFaltantes(): item ausente no HTML
 * significa "aconteceu zero vez".
 *
 * casa1T/fora1T/casa2T/fora2T ficam VAZIOS quando o painel nao veio no dump.
 * Aqui a ausencia significa "nao coletado", nao "zero" -- se o painel 1ST nao
 * foi montado no DOM, preencher com zero criaria partidas fantasma de 0
 * escanteios no primeiro tempo e envenenaria a estimativa. O motor consulta
 * temPeriodo() antes de usar a amostra.
 */
@Entity
@Table(name = "estatistica")
@Getter @Setter @NoArgsConstructor
public class Estatistica {

    public enum Periodo { PARTIDA, PRIMEIRO_TEMPO, SEGUNDO_TEMPO }
    public enum Lado { CASA, FORA }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partida_id", nullable = false)
    @JsonIgnore
    private Partida partida;

    // ---------------- Partida inteira ----------------

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

    // ---------------- Primeiro tempo ----------------

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "estatistica_casa_1t", joinColumns = @JoinColumn(name = "estatistica_id"))
    @MapKeyColumn(name = "campo")
    @Column(name = "valor")
    private Map<String, Float> casa1T = new HashMap<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "estatistica_fora_1t", joinColumns = @JoinColumn(name = "estatistica_id"))
    @MapKeyColumn(name = "campo")
    @Column(name = "valor")
    private Map<String, Float> fora1T = new HashMap<>();

    // ---------------- Segundo tempo ----------------

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "estatistica_casa_2t", joinColumns = @JoinColumn(name = "estatistica_id"))
    @MapKeyColumn(name = "campo")
    @Column(name = "valor")
    private Map<String, Float> casa2T = new HashMap<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "estatistica_fora_2t", joinColumns = @JoinColumn(name = "estatistica_id"))
    @MapKeyColumn(name = "campo")
    @Column(name = "valor")
    private Map<String, Float> fora2T = new HashMap<>();

    // ------------------------------------------------------------------
    // Acesso uniforme
    // ------------------------------------------------------------------

    /** Mapa bruto de um (lado, periodo). Nunca null; pode estar vazio. */
    @Transient
    @JsonIgnore
    public Map<String, Float> mapa(Lado lado, Periodo periodo) {
        boolean c = lado == Lado.CASA;
        return switch (periodo) {
            case PARTIDA        -> c ? casa   : fora;
            case PRIMEIRO_TEMPO -> c ? casa1T : fora1T;
            case SEGUNDO_TEMPO  -> c ? casa2T : fora2T;
        };
    }

    /** Valor de um campo, com default 0 -- so use quando temPeriodo() for true. */
    @Transient
    @JsonIgnore
    public float valor(Lado lado, Periodo periodo, String campo) {
        return mapa(lado, periodo).getOrDefault(campo, 0f);
    }

    /**
     * Este periodo foi realmente coletado?
     *
     * Checa os dois lados: um painel montado pela metade (so casa) e sinal de
     * dump incompleto e nao deve entrar na amostra.
     */
    @Transient
    @JsonIgnore
    public boolean temPeriodo(Periodo periodo) {
        return !mapa(Lado.CASA, periodo).isEmpty() && !mapa(Lado.FORA, periodo).isEmpty();
    }

    /** Total de cartoes de um lado num periodo, segundo a regra da casa. */
    @Transient
    @JsonIgnore
    public float cartoes(Lado lado, Periodo periodo, RegraCartoes regra) {
        return regra.total(valor(lado, periodo, "cartoesAmarelos"),
                           valor(lado, periodo, "cartoesVermelhos"));
    }

    /** Total de cartoes dos dois lados somados num periodo. */
    @Transient
    @JsonIgnore
    public float cartoesCombinados(Periodo periodo, RegraCartoes regra) {
        return cartoes(Lado.CASA, periodo, regra) + cartoes(Lado.FORA, periodo, regra);
    }

    /** Soma dos dois lados para um campo qualquer (escanteios, chutes, ...). */
    @Transient
    @JsonIgnore
    public float combinado(Periodo periodo, String campo) {
        return valor(Lado.CASA, periodo, campo) + valor(Lado.FORA, periodo, campo);
    }

    // ------------------------------------------------------------------
    // Manutencao
    // ------------------------------------------------------------------

    /**
     * Garante 0.0 em todos os campos ausentes da PARTIDA INTEIRA.
     *
     * Nao toca nos mapas de 1T/2T: la, vazio significa "nao coletado" e precisa
     * continuar significando isso.
     */
    public void preencherZerosFaltantes() {
        for (StatFields.StatMeta m : StatFields.CAMPOS) {
            casa.putIfAbsent(m.getCampo(), 0f);
            fora.putIfAbsent(m.getCampo(), 0f);
        }
    }

    /**
     * Completa com zeros um periodo que FOI coletado (pelo menos um campo veio).
     * Chame apenas apos confirmar que o painel existia no HTML.
     */
    public void preencherZerosPeriodo(Periodo periodo) {
        Map<String, Float> c = mapa(Lado.CASA, periodo);
        Map<String, Float> f = mapa(Lado.FORA, periodo);
        if (c.isEmpty() && f.isEmpty()) return;
        for (StatFields.StatMeta m : StatFields.CAMPOS) {
            c.putIfAbsent(m.getCampo(), 0f);
            f.putIfAbsent(m.getCampo(), 0f);
        }
    }

    /** Mapas ordenados pela metadata (util para respostas previsiveis). */
    @Transient
    @JsonIgnore
    public Map<String, Float> getCasaOrdenado() {
        return ordenar(casa);
    }

    @Transient
    @JsonIgnore
    public Map<String, Float> getForaOrdenado() {
        return ordenar(fora);
    }

    private Map<String, Float> ordenar(Map<String, Float> origem) {
        Map<String, Float> out = new LinkedHashMap<>();
        for (StatFields.StatMeta m : StatFields.CAMPOS) {
            out.put(m.getCampo(), origem.getOrDefault(m.getCampo(), 0f));
        }
        return out;
    }
}
