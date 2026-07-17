package com.footballstats.service;

import com.footballstats.dto.FusaoResultDTO;
import com.footballstats.model.Clube;
import com.footballstats.model.Partida;
import com.footballstats.repository.ClubeRepository;
import com.footballstats.repository.PartidaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Funde dois clubes que, na verdade, sao o mesmo (ex.: "Vasco" e "Vasco da Gama",
 * criados em duplicidade durante a importacao).
 *
 * Regra:
 *  - Todas as partidas do clube REMOVIDO passam a apontar para o clube MANTIDO
 *    (tanto no lado casa quanto no lado fora).
 *  - Se a transferencia gerar uma partida ja existente no clube mantido
 *    (mesma data + mesmos clubes), a partida duplicada e DESCARTADA, junto
 *    com suas estatisticas (cascade em Partida).
 *  - Partidas em que o clube removido enfrentaria a si mesmo apos a fusao
 *    (clube removido x clube mantido) sao descartadas: sao a mesma partida
 *    registrada duas vezes sob nomes diferentes.
 *  - Ao final, o clube removido e apagado. Resta apenas o clube mantido.
 */
@Service
public class FusaoClubeService {

    private final ClubeRepository clubeRepo;
    private final PartidaRepository partidaRepo;

    public FusaoClubeService(ClubeRepository clubeRepo, PartidaRepository partidaRepo) {
        this.clubeRepo = clubeRepo;
        this.partidaRepo = partidaRepo;
    }

    /**
     * @param manterId  id do clube que permanece (nome definitivo)
     * @param removerId id do clube duplicado, que sera absorvido e apagado
     */
    @Transactional
    public FusaoResultDTO fundir(Long manterId, Long removerId) {
        if (manterId == null || removerId == null) {
            return FusaoResultDTO.erro("Informe os dois clubes para fundir.");
        }
        if (manterId.equals(removerId)) {
            return FusaoResultDTO.erro("Selecione dois clubes diferentes.");
        }

        Clube manter = clubeRepo.findById(manterId).orElse(null);
        Clube remover = clubeRepo.findById(removerId).orElse(null);
        if (manter == null || remover == null) {
            return FusaoResultDTO.erro("Clube não encontrado.");
        }

        FusaoResultDTO r = new FusaoResultDTO();
        r.clubeMantidoId = manter.getId();
        r.clubeMantidoNome = manter.getNome();
        r.clubeRemovidoId = remover.getId();
        r.clubeRemovidoNome = remover.getNome();

        if (manter.getCampeonato() != null && remover.getCampeonato() != null
                && !manter.getCampeonato().getId().equals(remover.getCampeonato().getId())) {
            r.avisos.add("Os clubes pertencem a campeonatos diferentes ("
                    + manter.getCampeonato().getNome() + " e " + remover.getCampeonato().getNome()
                    + "). As partidas foram mantidas em " + manter.getCampeonato().getNome() + ".");
        }

        // Chaves (data|casaId|foraId) ja ocupadas pelo clube mantido.
        Set<String> ocupadas = new HashSet<>();
        for (Partida p : partidaRepo.findByClube(manterId)) {
            ocupadas.add(chave(p.getData(), p.getClubeCasa().getId(), p.getClubeFora().getId()));
        }

        List<Partida> descartar = new ArrayList<>();

        for (Partida p : partidaRepo.findByClube(removerId)) {
            boolean casaEraRemovido = p.getClubeCasa().getId().equals(removerId);
            boolean foraEraRemovido = p.getClubeFora().getId().equals(removerId);

            Long novaCasa = casaEraRemovido ? manterId : p.getClubeCasa().getId();
            Long novaFora = foraEraRemovido ? manterId : p.getClubeFora().getId();

            // Partida entre o removido e o mantido -> vira "clube x ele mesmo": descarta.
            if (novaCasa.equals(novaFora)) {
                descartar.add(p);
                r.avisos.add("Partida de " + p.getData() + " entre \"" + r.clubeRemovidoNome
                        + "\" e \"" + r.clubeMantidoNome + "\" descartada (mesmo clube após a fusão).");
                continue;
            }

            String k = chave(p.getData(), novaCasa, novaFora);
            if (ocupadas.contains(k)) {
                // Ja existe a mesma partida no clube mantido -> descarta a duplicada.
                descartar.add(p);
                continue;
            }

            if (casaEraRemovido) p.setClubeCasa(manter);
            if (foraEraRemovido) p.setClubeFora(manter);
            partidaRepo.save(p);
            ocupadas.add(k);
            r.partidasTransferidas++;
        }

        r.partidasDescartadas = descartar.size();
        if (!descartar.isEmpty()) {
            partidaRepo.deleteAll(descartar);
        }
        partidaRepo.flush();

        clubeRepo.delete(remover);

        r.ok = true;
        r.mensagem = String.format(
                "\"%s\" foi fundido em \"%s\": %d partida(s) transferida(s), %d duplicada(s) descartada(s).",
                r.clubeRemovidoNome, r.clubeMantidoNome, r.partidasTransferidas, r.partidasDescartadas);
        return r;
    }

    private String chave(LocalDate data, Long casaId, Long foraId) {
        return data + "|" + casaId + "|" + foraId;
    }
}
