package com.footballstats.repository;

import com.footballstats.model.Odd;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OddRepository extends JpaRepository<Odd, Long> {

    List<Odd> findByConfrontoId(Long confrontoId);

    List<Odd> findByConfrontoIdAndMercado(Long confrontoId, String mercado);

    /**
     * Apenas a captura MAIS RECENTE de cada (mercado, linha, selecao).
     *
     * O historico inteiro fica na tabela porque o movimento de linha e sinal,
     * mas a tela do bilhete quer o preco de agora. Sem este recorte, um mercado
     * capturado cinco vezes apareceria cinco vezes na comparacao de valor.
     */
    @Query("""
        select o from Odd o
        where o.confronto.id = :confrontoId
          and o.capturadoEm = (
              select max(o2.capturadoEm) from Odd o2
              where o2.confronto.id = o.confronto.id
                and o2.mercado = o.mercado
                and o2.selecao = o.selecao
                and (o2.linha = o.linha or (o2.linha is null and o.linha is null))
          )
        order by o.mercado, o.linha, o.selecao
        """)
    List<Odd> findAtuaisByConfronto(@Param("confrontoId") Long confrontoId);

    void deleteByConfrontoId(Long confrontoId);
}