package com.footballstats.service;

import com.footballstats.model.*;
import com.footballstats.probabilidade.AjusteDixonColes;
import com.footballstats.probabilidade.AjusteDixonColes.Ajuste;
import com.footballstats.probabilidade.AjusteDixonColes.PartidaBruta;
import com.footballstats.probabilidade.MatrizPlacares;
import com.footballstats.probabilidade.MercadoGols;
import com.footballstats.repository.ConfrontoRepository;
import com.footballstats.repository.PartidaRepository;
import com.footballstats.repository.ProjecaoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Gera as PROJECOES da familia de gols para um confronto.
 *
 * Fluxo: histórico do campeonato -> ajuste Dixon-Coles conjunto -> lambda dos
 * dois clubes -> matriz de placares -> recortes por mercado.
 *
 * CACHE DO AJUSTE, E POR QUE ELE E OBRIGATORIO
 * --------------------------------------------
 * O ajuste custa O(iteracoes x clubes x 80 x partidas) -- centenas de
 * milissegundos por campeonato, e cresce rapido. Ele NAO depende do confronto:
 * as forcas de ataque/defesa sao do campeonato inteiro. Refazer por jogo
 * multiplicaria o custo pelo numero de jogos do dia sem mudar uma virgula do
 * resultado. Cacheamos por campeonato e invalidamos quando entram partidas
 * novas.
 *
 * O QUE ESTE SERVICO NAO FAZ
 * --------------------------
 * Nao projeta escanteios, cartoes, chutes nem defesas. Esses nao saem da matriz
 * de placares -- precisam de estimadores proprios (Binomial Negativa para os
 * superdispersos, Binomial condicional para defesas). Ficaram de fora de
 * proposito: entregar meia dúzia de mercados corretos vale mais que dezessete
 * pela metade.
 */
@Service
public class ProjecaoService {

    private final PartidaRepository partidaRepo;
    private final ConfrontoRepository confrontoRepo;
    private final ProjecaoRepository projecaoRepo;

    public ProjecaoService(PartidaRepository partidaRepo, ConfrontoRepository confrontoRepo,
                           ProjecaoRepository projecaoRepo) {
        this.partidaRepo = partidaRepo;
        this.confrontoRepo = confrontoRepo;
        this.projecaoRepo = projecaoRepo;
    }

    /** Ajuste de um campeonato + o mapa clubeId -> indice usado nele. */
    public record AjusteCampeonato(Ajuste ajuste, Map<Long, Integer> indices,
                                   int amostra, Instant geradoEm) { }

    private final Map<Long, AjusteCampeonato> cache = new HashMap<>();

    /** Descarta o ajuste de um campeonato; chame após importar partidas. */
    public void invalidar(Long campeonatoId) {
        cache.remove(campeonatoId);
    }

    public void invalidarTudo() {
        cache.clear();
    }

    // ------------------------------------------------------------------

    /**
     * Ajusta (ou reaproveita) o modelo de um campeonato.
     *
     * Exige um minimo de partidas por clube. Com amostra curta o MLE encosta nas
     * bordas do intervalo de busca e produz forcas absurdas -- um clube com dois
     * jogos e duas goleadas viraria o melhor ataque do mundo. Melhor recusar e
     * dizer isso do que devolver numero bonito e errado.
     */
    public Optional<AjusteCampeonato> ajustar(Long campeonatoId) {
        AjusteCampeonato emCache = cache.get(campeonatoId);
        if (emCache != null) return Optional.of(emCache);

        List<Partida> partidas = partidaRepo.findByCampeonato(campeonatoId);
        if (partidas.size() < 20) return Optional.empty();

        Map<Long, Integer> indices = new HashMap<>();
        List<PartidaBruta> brutas = new ArrayList<>(partidas.size());
        for (Partida p : partidas) {
            if (p.getGolsCasa() == null || p.getGolsFora() == null) continue;
            int ic = indices.computeIfAbsent(p.getClubeCasa().getId(), k -> indices.size());
            int ifo = indices.computeIfAbsent(p.getClubeFora().getId(), k -> indices.size());
            brutas.add(new PartidaBruta(ic, ifo, p.getGolsCasa(), p.getGolsFora()));
        }
        if (indices.size() < 4 || brutas.size() < indices.size() * 2) return Optional.empty();

        Ajuste aj = AjusteDixonColes.estimar(indices.size(), brutas);
        AjusteCampeonato ac = new AjusteCampeonato(aj, indices, brutas.size(), Instant.now());
        cache.put(campeonatoId, ac);
        return Optional.of(ac);
    }

    // ------------------------------------------------------------------

    public static class Resultado {
        public boolean sucesso;
        public String mensagem;
        public Long confrontoId;
        public String modelo;
        public double lambdaCasa;
        public double lambdaFora;
        public int amostra;
        public double rho;
        public double mando;
        public boolean convergiu;
        public List<Projecao> projecoes = new ArrayList<>();

        static Resultado falha(String m) {
            Resultado r = new Resultado();
            r.sucesso = false;
            r.mensagem = m;
            return r;
        }
    }

    @Transactional
    public Resultado projetar(Long confrontoId, MatrizPlacares.Modelo modelo) {
        Confronto c = confrontoRepo.findById(confrontoId).orElse(null);
        if (c == null) return Resultado.falha("Confronto " + confrontoId + " não existe.");

        Long campId = c.getClubeCasa().getCampeonato().getId();
        Optional<AjusteCampeonato> opt = ajustar(campId);
        if (opt.isEmpty()) {
            return Resultado.falha("Histórico insuficiente no campeonato "
                    + c.getClubeCasa().getCampeonato().getNome()
                    + " para ajustar o modelo. Importe mais partidas.");
        }
        AjusteCampeonato ac = opt.get();

        Integer ic = ac.indices().get(c.getClubeCasa().getId());
        Integer ifo = ac.indices().get(c.getClubeFora().getId());
        if (ic == null || ifo == null) {
            return Resultado.falha("Um dos clubes não tem partidas no histórico ajustado.");
        }

        double[] lam = ac.ajuste().golsEsperados(ic, ifo);
        double lc = lam[0], lf = lam[1];

        // O rho estimado substitui o padrao: foi medido nestes dados.
        MatrizPlacares.Params params = MatrizPlacares.Params.padrao().comRho(ac.ajuste().rho());
        MercadoGols g = MercadoGols.de(modelo, lc, lf, params);

        projecaoRepo.deleteByConfrontoId(confrontoId);

        Resultado r = new Resultado();
        r.sucesso = true;
        r.confrontoId = confrontoId;
        r.modelo = modelo.name();
        r.lambdaCasa = lc;
        r.lambdaFora = lf;
        r.amostra = ac.amostra();
        r.rho = ac.ajuste().rho();
        r.mando = ac.ajuste().mando();
        r.convergiu = ac.ajuste().convergiu();

        List<Projecao> lista = new ArrayList<>();

        // ---- ambos marcam (partida) ----
        double btts = g.btts();
        lista.add(nova(c, "btts", null, null, "SIM", btts, modelo, lc, lf, ac.amostra()));
        lista.add(nova(c, "btts", null, null, "NAO", 1 - btts, modelo, lc, lf, ac.amostra()));

        // ---- ambos marcam por tempo ----
        double b1 = MercadoGols.bttsNoTempo(lc, lf, true);
        double b2 = MercadoGols.bttsNoTempo(lc, lf, false);
        lista.add(nova(c, "btts_1t", null, null, "SIM", b1, modelo, lc, lf, ac.amostra()));
        lista.add(nova(c, "btts_1t", null, null, "NAO", 1 - b1, modelo, lc, lf, ac.amostra()));
        lista.add(nova(c, "btts_2t", null, null, "SIM", b2, modelo, lc, lf, ac.amostra()));
        lista.add(nova(c, "btts_2t", null, null, "NAO", 1 - b2, modelo, lc, lf, ac.amostra()));

        // ---- total de gols ----
        for (double linha : linhasDe("total_gols")) {
            double p = g.over(linha);
            lista.add(nova(c, "total_gols", linha, null, "MAIS", p, modelo, lc, lf, ac.amostra()));
            lista.add(nova(c, "total_gols", linha, null, "MENOS", 1 - p, modelo, lc, lf, ac.amostra()));
        }

        // ---- faixa de gols (piso 1: o 0-0 perde em todas) ----
        for (double ate : linhasDe("faixa_gols")) {
            double p = g.faixaGols(1, ate);
            lista.add(nova(c, "faixa_gols", 1d, ate, "SIM", p, modelo, lc, lf, ac.amostra()));
            lista.add(nova(c, "faixa_gols", 1d, ate, "NAO", 1 - p, modelo, lc, lf, ac.amostra()));
        }

        // ---- margem de vitoria, por lado ----
        for (double linha : linhasDe("margem_vitoria")) {
            lista.add(nova(c, "margem_vitoria", linha, null, "CASA",
                    g.onde(MercadoGols.margemCasaAcimaDe(linha)), modelo, lc, lf, ac.amostra()));
            lista.add(nova(c, "margem_vitoria", linha, null, "FORA",
                    g.onde(MercadoGols.margemForaAcimaDe(linha)), modelo, lc, lf, ac.amostra()));
        }

        // ---- tempo com mais gols ----
        Map<String, Double> t = MercadoGols.tempoComMaisGols(lc, lf);
        t.forEach((sel, p) ->
                lista.add(nova(c, "tempo_mais_gols", null, null, sel, p, modelo, lc, lf, ac.amostra())));

        projecaoRepo.saveAll(lista);
        r.projecoes = lista;
        r.mensagem = lista.size() + " projeção(ões) para " + c.descricao()
                + String.format("  (λ %.2f x %.2f, amostra %d)", lc, lf, ac.amostra());
        return r;
    }

    private List<Double> linhasDe(String codigo) {
        return Mercados.porCodigo(codigo)
                .map(Mercados.MercadoMeta::getLinhas)
                .orElse(List.of());
    }

    private Projecao nova(Confronto c, String mercado, Double linha, Double ate, String selecao,
                          double p, MatrizPlacares.Modelo modelo, double lc, double lf, int amostra) {
        Projecao x = new Projecao();
        x.setConfronto(c);
        x.setMercado(mercado);
        x.setLinha(linha);
        x.setLinhaAte(ate);
        x.setSelecao(selecao);
        x.setProbabilidade(p);
        x.setModelo(modelo.name());
        x.setLambdaCasa(lc);
        x.setLambdaFora(lf);
        x.setAmostra(amostra);
        return x;
    }
}