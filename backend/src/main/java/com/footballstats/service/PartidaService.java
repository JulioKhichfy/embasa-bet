package com.footballstats.service;

import com.footballstats.dto.*;
import com.footballstats.model.*;
import com.footballstats.parser.SofaScoreParser;
import com.footballstats.repository.ClubeRepository;
import com.footballstats.repository.CampeonatoRepository;
import com.footballstats.repository.PartidaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PartidaService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final PartidaRepository partidaRepo;
    private final ClubeRepository clubeRepo;
    private final CampeonatoRepository campeonatoRepo;
    private final SofaScoreParser parser;

    public PartidaService(PartidaRepository partidaRepo, ClubeRepository clubeRepo,
                          CampeonatoRepository campeonatoRepo, SofaScoreParser parser) {
        this.partidaRepo = partidaRepo;
        this.clubeRepo = clubeRepo;
        this.campeonatoRepo = campeonatoRepo;
        this.parser = parser;
    }

    public List<Partida> listar() { return partidaRepo.findAll(); }

    // ------------------------------------------------------------------
    // IMPORT do HTML do SofaScore
    // ------------------------------------------------------------------

    /**
     * @param html          conteudo do arquivo partida.html
     * @param campeonatoId  campeonato onde criar clubes ausentes
     * @param clubeCasaId   (opcional) id do clube que sabemos jogar em CASA;
     *                      usado para casar o nome corretamente. Pode ser null.
     */
    @Transactional
    public ImportResultDTO importarHtml(String html, Long campeonatoId, Long clubeCasaId) {
        SofaScoreParser.Resultado res = parser.parse(html);

        if (res.nomeClubeCasa == null || res.nomeClubeFora == null || res.data == null) {
            return ImportResultDTO.ignorada("Não foi possível extrair cabeçalho (clubes/data) do HTML.");
        }

        Clube casa = resolverClube(res.nomeClubeCasa, campeonatoId, clubeCasaId);
        Clube fora = resolverClube(res.nomeClubeFora, campeonatoId, null);

        // Duplicidade: mesma data + mesmos clubes -> ignora
        if (partidaRepo.existsByDataAndClubeCasaIdAndClubeForaId(res.data, casa.getId(), fora.getId())) {
            ImportResultDTO d = ImportResultDTO.ignorada(
                    "Partida já existente (" + casa.getNome() + " x " + fora.getNome() + " em " + res.data.format(FMT) + "). Ignorada.");
            d.clubeCasa = casa.getNome(); d.clubeFora = fora.getNome(); d.data = res.data.format(FMT);
            return d;
        }

        Partida p = new Partida();
        p.setData(res.data);
        p.setClubeCasa(casa);
        p.setClubeFora(fora);
        p.setGolsCasa(res.golsCasa);
        p.setGolsFora(res.golsFora);

        Estatistica est = new Estatistica();
        est.setPartida(p);
        est.setCasa(new HashMap<>(res.casa));
        est.setFora(new HashMap<>(res.fora));
        est.preencherZerosFaltantes();
        p.setEstatistica(est);

        Partida salva = partidaRepo.save(p);

        ImportResultDTO d = new ImportResultDTO();
        d.importada = true;
        d.mensagem = "Partida importada com sucesso.";
        d.partidaId = salva.getId();
        d.clubeCasa = casa.getNome();
        d.clubeFora = fora.getNome();
        d.golsCasa = res.golsCasa;
        d.golsFora = res.golsFora;
        d.data = res.data.format(FMT);
        return d;
    }

    /**
     * Ordena os nomes de arquivo pelo número N em "partida_N.html".
     * Retorna a lista de índices na ordem correta de processamento.
     */
    public List<Integer> ordenarIndicesPorNumero(List<String> nomes) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < nomes.size(); i++) idx.add(i);
        idx.sort(Comparator.comparingInt(i -> extrairNumero(nomes.get(i))));
        return idx;
    }

    /** Extrai o N de "partida_N.html"; se não houver, retorna Integer.MAX_VALUE (vai pro fim). */
    private int extrairNumero(String nome) {
        if (nome == null) return Integer.MAX_VALUE;
        Matcher m = Pattern.compile("(\\d+)").matcher(nome);
        int ultimo = Integer.MAX_VALUE;
        while (m.find()) ultimo = Integer.parseInt(m.group(1)); // usa o último número do nome
        return ultimo;
    }

    /** Encontra clube por nome no campeonato; cria se ausente. */
    private Clube resolverClube(String nome, Long campeonatoId, Long preferidoId) {
        if (preferidoId != null) {
            Optional<Clube> pref = clubeRepo.findById(preferidoId);
            if (pref.isPresent()) return pref.get();
        }
        Optional<Clube> existente = clubeRepo.findByNomeIgnoreCaseAndCampeonatoId(nome, campeonatoId);
        if (existente.isPresent()) return existente.get();

        Campeonato camp = campeonatoRepo.findById(campeonatoId)
                .orElseThrow(() -> new RuntimeException("Campeonato " + campeonatoId + " não encontrado"));
        Clube c = new Clube();
        c.setNome(nome);
        c.setCampeonato(camp);
        return clubeRepo.save(c);
    }

    // ------------------------------------------------------------------
    // DETALHE do clube: ultimas N + medias, filtro TODOS/CASA/FORA
    // ------------------------------------------------------------------

    public ClubeDetalheDTO detalheClube(Long clubeId, String filtro, int limite) {
        Clube clube = clubeRepo.findById(clubeId).orElseThrow(() -> new RuntimeException("Clube não encontrado"));
        String f = (filtro == null ? "TODOS" : filtro.toUpperCase());

        List<Partida> partidas = switch (f) {
            case "CASA" -> partidaRepo.findEmCasa(clubeId);
            case "FORA" -> partidaRepo.findFora(clubeId);
            default     -> partidaRepo.findByClube(clubeId);
        };
        if (limite > 0 && partidas.size() > limite) partidas = partidas.subList(0, limite);

        ClubeDetalheDTO dto = new ClubeDetalheDTO();
        dto.clubeId = clubeId;
        dto.clubeNome = clube.getNome();
        dto.filtro = f;
        dto.totalPartidas = partidas.size();
        dto.partidas = new ArrayList<>();

        Map<String, Float> soma = new LinkedHashMap<>();
        for (StatFields.StatMeta m : StatFields.CAMPOS) soma.put(m.getCampo(), 0f);
        float somaPontos = 0, somaGF = 0, somaGS = 0;

        for (Partida p : partidas) {
            boolean emCasa = p.getClubeCasa().getId().equals(clubeId);
            int gf = emCasa ? p.getGolsCasa() : p.getGolsFora();
            int gs = emCasa ? p.getGolsFora() : p.getGolsCasa();
            int pts = emCasa ? p.pontosCasa() : p.pontosFora();

            PartidaResumoDTO pr = new PartidaResumoDTO();
            pr.partidaId = p.getId();
            pr.data = p.getData().format(FMT);
            pr.adversario = emCasa ? p.getClubeFora().getNome() : p.getClubeCasa().getNome();
            pr.emCasa = emCasa;
            pr.golsFeitos = gf;
            pr.golsSofridos = gs;
            pr.pontos = pts;
            pr.resultado = pts == 3 ? "V" : (pts == 1 ? "E" : "D");

            if (p.getEstatistica() != null) {
                Map<String, Float> lado = emCasa ? p.getEstatistica().getCasa() : p.getEstatistica().getFora();
                Map<String, Float> ladoAdv = emCasa ? p.getEstatistica().getFora() : p.getEstatistica().getCasa();
                Map<String, Float> estat = new LinkedHashMap<>();
                Map<String, Float> estatAdv = new LinkedHashMap<>();
                for (StatFields.StatMeta m : StatFields.CAMPOS) {
                    float v = lado.getOrDefault(m.getCampo(), 0f);
                    estat.put(m.getCampo(), v);
                    estatAdv.put(m.getCampo(), ladoAdv.getOrDefault(m.getCampo(), 0f));
                    soma.merge(m.getCampo(), v, Float::sum);
                }
                pr.estatisticas = estat;
                pr.estatisticasAdversario = estatAdv;
            }
            dto.partidas.add(pr);

            somaPontos += pts; somaGF += gf; somaGS += gs;
        }

        int n = Math.max(1, partidas.size());
        Map<String, Float> medias = new LinkedHashMap<>();
        for (StatFields.StatMeta m : StatFields.CAMPOS) medias.put(m.getCampo(), soma.get(m.getCampo()) / n);
        dto.medias = medias;
        dto.mediaPontos = somaPontos / n;
        dto.mediaGolsFeitos = somaGF / n;
        dto.mediaGolsSofridos = somaGS / n;
        return dto;
    }

    // ------------------------------------------------------------------
    // COMPARACAO entre 2 clubes
    // ------------------------------------------------------------------

    public ComparacaoDTO comparar(Long clubeAId, Long clubeBId, String filtro, int limite) {
        ClubeDetalheDTO a = detalheClube(clubeAId, filtro, limite);
        ClubeDetalheDTO b = detalheClube(clubeBId, filtro, limite);

        ComparacaoDTO c = new ComparacaoDTO();
        c.clubeAId = clubeAId; c.clubeANome = a.clubeNome;
        c.clubeBId = clubeBId; c.clubeBNome = b.clubeNome;
        c.filtro = a.filtro;
        c.nPartidas = limite;
        c.mediasA = a.medias;
        c.mediasB = b.medias;
        c.campos = new ArrayList<>();
        for (StatFields.StatMeta m : StatFields.CAMPOS) {
            c.campos.add(new CampoMetaDTO(m.getCampo(), m.getRotulo(), m.getCategoria(), m.getTipo().name()));
        }
        return c;
    }
}