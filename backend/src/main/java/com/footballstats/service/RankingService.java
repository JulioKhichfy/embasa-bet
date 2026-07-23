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

    /**
     * Quesitos que saem do mapa de estatisticas.
     *
     * NOTA sobre os pedidos originais:
     *  - "faltas realizadas"  -> campo "faltas" (existe).
     *  - "faltas sofridas"    -> NAO existe um campo dedicado. O SofaScore so
     *    exporta "Faltas sofridas no terco final" (faltasSofridasTercoFinal),
     *    que e um recorte parcial. Incluimos esse como aproximacao rotulada.
     *  - "cabecadas"          -> NAO existe NENHUM campo no SofaScore/StatFields.
     *    Sem coleta na origem, nao ha como ranquear. Fica de fora ate haver dado.
     */
    private static final String[][] QUESITOS_STAT = {
            { "finalizacoes",            "Chutes (total)" },
            { "finalizacoesNoGol",       "Chutes ao gol" },
            { "cartoesAmarelos",         "Cartões amarelos" },
            { "cartoesVermelhos",        "Cartões vermelhos" },
            { "escanteios",              "Escanteios" },
            { "faltas",                  "Faltas realizadas" },
            { "faltasSofridasTercoFinal","Faltas sofridas (terço final)" },
            { "tirosDeMeta",             "Tiros de meta" },
            { "impedimentos",            "Impedimentos" }
    };

    /** Quesitos derivados do placar. */
    private static final String[][] QUESITOS_PLACAR = {
            { "golsFeitos",   "Gols realizados" },
            { "golsSofridos", "Gols sofridos" },
            { "saldoGols",    "Saldo de gols" }
    };

    public RankingDTO ranking(String filtro, int limite) {
        return ranking(filtro, limite, null);
    }

    /**
     * @param campeonatoId  se != null, ranqueia SO os clubes desse campeonato.
     *   E o comportamento correto: comparar cartoes/gols entre times do mesmo
     *   campeonato. Sem isso, findAll() mistura campeonatos e as posicoes
     *   estouram o numero de clubes (ex.: 48o num campeonato de 16 times).
     */
    /** Deriva o campeonato a partir de UM clube comparado e ranqueia so ele. */
    public RankingDTO rankingPorClube(String filtro, int limite, Long clubeId) {
        Long campId = (clubeId == null) ? null :
                clubeRepo.findById(clubeId)
                        .map(c -> c.getCampeonato() != null ? c.getCampeonato().getId() : null)
                        .orElse(null);
        return ranking(filtro, limite, campId);
    }

    public RankingDTO ranking(String filtro, int limite, Long campeonatoId) {
        String f = (filtro == null ? "TODOS" : filtro.toUpperCase());

        RankingDTO dto = new RankingDTO();
        dto.filtro = f;
        dto.limite = limite;

        List<Clube> clubes = (campeonatoId != null)
                ? clubeRepo.findByCampeonatoId(campeonatoId)
                : clubeRepo.findAll();

        // Pre-carrega o detalhe de cada clube uma unica vez (evita N x quesitos consultas)
        List<ClubeDetalheDTO> detalhes = new ArrayList<>();
        for (Clube c : clubes) {
            try {
                detalhes.add(partidaService.detalheClube(c.getId(), f, limite));
            } catch (Exception ignored) { /* clube sem partidas ou erro pontual -> ignora */ }
        }

        // Classificacao (pontos) primeiro: e a resposta para "quem esta melhor".
        dto.quesitos.add(montarQuesitoPontos(clubes, detalhes));

        for (String[] q : QUESITOS_STAT) {
            dto.quesitos.add(montarQuesitoStat(q[0], q[1], clubes, detalhes));
        }
        for (String[] q : QUESITOS_PLACAR) {
            dto.quesitos.add(montarQuesitoPlacar(q[0], q[1], clubes, detalhes));
        }
        return dto;
    }

    /**
     * Quesito "Posição na tabela": ranqueia por PONTOS (V*3 + E) no recorte de
     * partidas considerado (filtro + limite). O 'total' e a soma de pontos e a
     * 'media' os pontos por jogo (aproveitamento). A ordenacao decrescente ja
     * produz a posicao (1o = mais pontos), coerente com a classificacao do
     * dashboard. Desempate por pontos/jogo e depois nome.
     */
    private RankingDTO.Quesito montarQuesitoPontos(List<Clube> clubes, List<ClubeDetalheDTO> detalhes) {
        RankingDTO.Quesito q = new RankingDTO.Quesito("posicaoTabela", "Posição na tabela (pontos)");
        for (ClubeDetalheDTO d : detalhes) {
            if (d.partidas == null || d.partidas.isEmpty()) continue;
            float pontos = 0f;
            for (PartidaResumoDTO p : d.partidas) pontos += p.pontos;
            q.itens.add(novoItem(clubes, d, pontos));
        }
        ordenar(q);
        return q;
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