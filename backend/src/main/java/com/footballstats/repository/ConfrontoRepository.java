package com.footballstats.repository;

import com.footballstats.model.Confronto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ConfrontoRepository extends JpaRepository<Confronto, Long> {

    List<Confronto> findByDataOrderByHoraAsc(LocalDate data);

    Optional<Confronto> findByDataAndClubeCasaIdAndClubeForaId(LocalDate data, Long casaId, Long foraId);

    boolean existsByDataAndClubeCasaIdAndClubeForaId(LocalDate data, Long casaId, Long foraId);

    Optional<Confronto> findByIdExterno(String idExterno);
}