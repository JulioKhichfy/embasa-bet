package com.footballstats.service;

import com.footballstats.dto.ClubeDetalheDTO;
import com.footballstats.dto.PartidaResumoDTO;
import com.footballstats.dto.RankingDTO;
import com.footballstats.model.Clube;
import com.footballstats.repository.ClubeRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Monta o ranking de TODOS os clubes cadastrados por quesito, respeitando o
 * filtro TODOS/CASA/FORA e o limite de N partidas.
 *
 * Quesitos consolidados (9):
 *   cartoesAmarelos, cartoesVermelhos, finalizacoes, finalizacoesNoGol,
 *   escanteios, impedimentos, golsFeitos, golsSofridos, saldoGols
 *
 * golsFeitos/golsSofridos/saldoGols nao vem do mapa de estatisticas: sao
 * derivados do placar de cada partida (PartidaResumoDTO).
 */
@Service
public class RankingService {

    private final ClubeRepository clubeRepo;
    private final PartidaService partidaService;

    public RankingService(ClubeRepository clubeRepo, PartidaService partidaService) {
        this.clubeRepo = clubeRepo;
        this.partidaService = partidaService;
    }

    /** Quesitos que saem do mapa de estatisticas. */
    private static final String[][] QUESITOS_STAT = {
            { "cartoesAmarelos",   "Cartões amarelos" },
            { "cartoesVermelhos",  "Cartões vermelhos" },
            { "finalizacoes",      "Finalizações (chutes)" },
            { "finalizacoesNoGol", "Chutes ao gol" },
            { "escanteios",        "Escanteios" },
            { "impedimentos",      "Impedimentos" }
    };

    /** Quesitos derivados do placar. */
    private static final String[][] QUESITOS_PLACAR = {
            { "golsFeitos",   "Gols realizados" },
            { "golsSofridos", "Gols sofridos" },
            { "saldoGols",    "Saldo de gols" }
    };

    public RankingDTO ranking(String filtro, int limite) {
        String f = (filtro == null ? "TODOS" : filtro.toUpperCase());

        RankingDTO dto = new RankingDTO();
        dto.filtro = f;
        dto.limite = limite;

        List<Clube> clubes = clubeRepo.findAll();

        // Pre-carrega o detalhe de cada clube uma unica vez (evita N x quesitos consultas)
        List<ClubeDetalheDTO> detalhes = new ArrayList<>();
        for (Clube c : clubes) {
            try {
                detalhes.add(partidaService.detalheClube(c.getId(), f, limite));
            } catch (Exception ignored) { /* clube sem partidas ou erro pontual -> ignora */ }
        }

        for (String[] q : QUESITOS_STAT) {
            dto.quesitos.add(montarQuesitoStat(q[0], q[1], clubes, detalhes));
        }
        for (String[] q : QUESITOS_PLACAR) {
            dto.quesitos.add(montarQuesitoPlacar(q[0], q[1], clubes, detalhes));
        }
        return dto;
    }

    private RankingDTO.Quesito montarQuesitoStat(String campo, String rotulo,
                                                 List<Clube> clubes, List<ClubeDetalheDTO> detalhes) {
        RankingDTO.Quesito q = new RankingDTO.Quesito(campo, rotulo);
        for (int i = 0; i < detalhes.size(); i++) {
            ClubeDetalheDTO d = detalhes.get(i);
            if (d.partidas == null || d.partidas.isEmpty()) continue;
            float total = 0f;
            for (PartidaResumoDTO p : d.partidas) {
                if (p.estatisticas != null) total += p.estatisticas.getOrDefault(campo, 0f);
            }
            q.itens.add(novoItem(clubes, d, total));
        }
        ordenar(q);
        return q;
    }

    private RankingDTO.Quesito montarQuesitoPlacar(String chave, String rotulo,
                                                   List<Clube> clubes, List<ClubeDetalheDTO> detalhes) {
        RankingDTO.Quesito q = new RankingDTO.Quesito(chave, rotulo);
        for (ClubeDetalheDTO d : detalhes) {
            if (d.partidas == null || d.partidas.isEmpty()) continue;
            float total = 0f;
            for (PartidaResumoDTO p : d.partidas) {
                switch (chave) {
                    case "golsFeitos"   -> total += p.golsFeitos;
                    case "golsSofridos" -> total += p.golsSofridos;
                    case "saldoGols"    -> total += (p.golsFeitos - p.golsSofridos);
                    default -> { }
                }
            }
            q.itens.add(novoItem(clubes, d, total));
        }
        ordenar(q);
        return q;
    }

    private RankingDTO.Item novoItem(List<Clube> clubes, ClubeDetalheDTO d, float total) {
        int jogos = Math.max(1, d.totalPartidas);
        String campeonato = clubes.stream()
                .filter(c -> c.getId().equals(d.clubeId))
                .findFirst()
                .map(c -> c.getCampeonato() != null ? c.getCampeonato().getNome() : "")
                .orElse("");
        return new RankingDTO.Item(d.clubeId, d.clubeNome, campeonato, d.totalPartidas, total, total / jogos);
    }

    /** Maior primeiro; desempate por media, depois nome. */
    private void ordenar(RankingDTO.Quesito q) {
        q.itens.sort(Comparator
                .comparing((RankingDTO.Item it) -> it.total).reversed()
                .thenComparing(Comparator.comparing((RankingDTO.Item it) -> it.media).reversed())
                .thenComparing(it -> it.clubeNome));
    }
}