package com.footballstats.repository;

import com.footballstats.model.Nacao;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface NacaoRepository extends JpaRepository<Nacao, Long> {
    boolean existsByNomeIgnoreCase(String nome);
    Optional<Nacao> findByNomeIgnoreCase(String nome);
}
