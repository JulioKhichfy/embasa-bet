package com.footballstats.repository;

import com.footballstats.model.Campeonato;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CampeonatoRepository extends JpaRepository<Campeonato, Long> {
    List<Campeonato> findByNacaoId(Long nacaoId);
    Optional<Campeonato> findByNomeIgnoreCaseAndNacaoId(String nome, Long nacaoId);

    /**
     * Busca pelo id do torneio na fonte. Sobrevive a troca de patrocinador no
     * nome ("Brasileirão Assaí" -> "Brasileirão Betano"), que senão partiria o
     * histórico em dois campeonatos distintos.
     */
    Optional<Campeonato> findByIdExterno(Long idExterno);
}