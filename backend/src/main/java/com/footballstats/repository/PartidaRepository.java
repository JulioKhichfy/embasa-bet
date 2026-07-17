package com.footballstats.repository;

import com.footballstats.model.Partida;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PartidaRepository extends JpaRepository<Partida, Long> {

    boolean existsByDataAndClubeCasaIdAndClubeForaId(LocalDate data, Long casaId, Long foraId);

    Optional<Partida> findByDataAndClubeCasaIdAndClubeForaId(LocalDate data, Long casaId, Long foraId);

    // Todas as partidas de um clube (casa OU fora), mais recentes primeiro
    @Query("SELECT p FROM Partida p WHERE p.clubeCasa.id = :clubeId OR p.clubeFora.id = :clubeId ORDER BY p.data DESC")
    List<Partida> findByClube(@Param("clubeId") Long clubeId);

    @Query("SELECT p FROM Partida p WHERE p.clubeCasa.id = :clubeId ORDER BY p.data DESC")
    List<Partida> findEmCasa(@Param("clubeId") Long clubeId);

    @Query("SELECT p FROM Partida p WHERE p.clubeFora.id = :clubeId ORDER BY p.data DESC")
    List<Partida> findFora(@Param("clubeId") Long clubeId);

    // Partidas que envolvem qualquer clube de um campeonato (para exclusao em cascata)
    @Query("SELECT p FROM Partida p WHERE p.clubeCasa.campeonato.id = :campId OR p.clubeFora.campeonato.id = :campId")
    List<Partida> findByCampeonato(@Param("campId") Long campId);

    // Partidas que envolvem qualquer clube de uma nacao
    @Query("SELECT p FROM Partida p WHERE p.clubeCasa.campeonato.nacao.id = :nacaoId OR p.clubeFora.campeonato.nacao.id = :nacaoId")
    List<Partida> findByNacao(@Param("nacaoId") Long nacaoId);
}
