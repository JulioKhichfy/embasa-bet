package com.footballstats.repository;

import com.footballstats.model.Projecao;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjecaoRepository extends JpaRepository<Projecao, Long> {

    List<Projecao> findByConfrontoId(Long confrontoId);

    void deleteByConfrontoId(Long confrontoId);
}