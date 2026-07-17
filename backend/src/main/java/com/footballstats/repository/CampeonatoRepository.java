package com.footballstats.repository;

import com.footballstats.model.Campeonato;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CampeonatoRepository extends JpaRepository<Campeonato, Long> {
    List<Campeonato> findByNacaoId(Long nacaoId);
    Optional<Campeonato> findByNomeIgnoreCaseAndNacaoId(String nome, Long nacaoId);
}
