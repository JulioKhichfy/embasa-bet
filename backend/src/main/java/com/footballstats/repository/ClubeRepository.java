package com.footballstats.repository;

import com.footballstats.model.Clube;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ClubeRepository extends JpaRepository<Clube, Long> {
    List<Clube> findByCampeonatoId(Long campeonatoId);
    Optional<Clube> findByNomeIgnoreCaseAndCampeonatoId(String nome, Long campeonatoId);
    Optional<Clube> findFirstByNomeIgnoreCase(String nome);

    /**
     * Busca pela identidade do clube na fonte (SofaScore).
     *
     * Primeiro degrau da cascata do ClubeResolver: quando o id bate, nenhum nome
     * precisa ser comparado -- e nome e justamente o que causa duplicidade.
     */
    Optional<Clube> findByIdExterno(Long idExterno);
}