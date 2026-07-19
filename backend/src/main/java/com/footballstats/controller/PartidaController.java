package com.footballstats.controller;

import com.footballstats.dto.*;
import com.footballstats.model.Partida;
import com.footballstats.model.StatFields;
import com.footballstats.service.PartidaService;
import com.footballstats.service.RankingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/partidas")
public class PartidaController {

    private final PartidaService service;
    private final RankingService rankingService;

    public PartidaController(PartidaService service, RankingService rankingService) {
        this.service = service;
        this.rankingService = rankingService;
    }

    @GetMapping
    public List<Partida> listar() { return service.listar(); }

    /** Upload do partida.html do SofaScore. */
    @PostMapping("/importar")
    public ResponseEntity<ImportResultDTO> importar(
            @RequestParam("arquivo") MultipartFile arquivo,
            @RequestParam Long campeonatoId,
            @RequestParam(required = false) Long clubeCasaId) throws IOException {
        String html = new String(arquivo.getBytes(), StandardCharsets.UTF_8);
        ImportResultDTO r = service.importarHtml(html, campeonatoId, clubeCasaId);
        return ResponseEntity.ok(r);
    }

    /** Upload de vários arquivos partida_N.html de uma vez, para o mesmo clube. */
    @PostMapping("/importar-lote")
    public ResponseEntity<ImportLoteDTO> importarLote(
            @RequestParam("arquivos") MultipartFile[] arquivos,
            @RequestParam Long campeonatoId,
            @RequestParam(required = false) Long clubeCasaId) throws IOException {

        List<String> nomes = new ArrayList<>();
        List<String> htmls = new ArrayList<>();
        for (MultipartFile f : arquivos) {
            nomes.add(f.getOriginalFilename());
            htmls.add(new String(f.getBytes(), StandardCharsets.UTF_8));
        }

        ImportLoteDTO lote = new ImportLoteDTO();
        // processa na ordem de partida_1, partida_2, ... (cada importarHtml em sua própria transação)
        for (int idx : service.ordenarIndicesPorNumero(nomes)) {
            String nome = nomes.get(idx);
            lote.total++;
            try {
                ImportResultDTO r = service.importarHtml(htmls.get(idx), campeonatoId, clubeCasaId);
                r.nomeArquivo = nome;
                if (r.importada) lote.importadas++; else lote.ignoradas++;
                lote.resultados.add(r);
            } catch (Exception e) {
                lote.comErro++;
                ImportResultDTO err = ImportResultDTO.ignorada("Erro ao processar: " + e.getMessage());
                err.nomeArquivo = nome;
                lote.resultados.add(err);
            }
        }
        lote.mensagem = String.format("%d arquivo(s): %d importada(s), %d ignorada(s), %d com erro.",
                lote.total, lote.importadas, lote.ignoradas, lote.comErro);
        return ResponseEntity.ok(lote);
    }

    /**
     * UPLOAD GLOBAL: N arquivos de QUALQUER clube do campeonato de uma vez.
     * O clube da CASA sai do nome do arquivo, no padrao "<Clube>_<eventId>.html"
     * (ex.: Chapecoense_15237944.html). Nao precisa mais entrar em cada card.
     */
    @PostMapping("/importar-global")
    public ResponseEntity<ImportLoteDTO> importarGlobal(
            @RequestParam("arquivos") MultipartFile[] arquivos,
            @RequestParam Long campeonatoId) throws IOException {

        List<String> nomes = new ArrayList<>();
        List<String> htmls = new ArrayList<>();
        for (MultipartFile f : arquivos) {
            nomes.add(f.getOriginalFilename());
            htmls.add(new String(f.getBytes(), StandardCharsets.UTF_8));
        }

        ImportLoteDTO lote = new ImportLoteDTO();
        for (int i = 0; i < nomes.size(); i++) {
            String nome = nomes.get(i);
            lote.total++;
            try {
                ImportResultDTO r = service.importarHtmlComNomeArquivo(htmls.get(i), nome, campeonatoId);
                if (r.importada) lote.importadas++; else lote.ignoradas++;
                lote.resultados.add(r);
            } catch (Exception e) {
                lote.comErro++;
                ImportResultDTO err = ImportResultDTO.ignorada("Erro ao processar: " + e.getMessage());
                err.nomeArquivo = nome;
                lote.resultados.add(err);
            }
        }
        lote.mensagem = String.format("%d arquivo(s): %d importada(s), %d ignorada(s), %d com erro.",
                lote.total, lote.importadas, lote.ignoradas, lote.comErro);
        return ResponseEntity.ok(lote);
    }

    /** Detalhe do clube: ultimas N partidas + medias. filtro=TODOS|CASA|FORA */
    @GetMapping("/clube/{clubeId}")
    public ClubeDetalheDTO detalhe(
            @PathVariable Long clubeId,
            @RequestParam(defaultValue = "TODOS") String filtro,
            @RequestParam(defaultValue = "5") int limite) {
        return service.detalheClube(clubeId, filtro, limite);
    }

    /** Comparacao entre 2 clubes. */
    @GetMapping("/comparacao")
    public ComparacaoDTO comparar(
            @RequestParam Long a,
            @RequestParam Long b,
            @RequestParam(defaultValue = "TODOS") String filtro,
            @RequestParam(defaultValue = "5") int limite) {
        return service.comparar(a, b, filtro, limite);
    }

    /** Ranking de TODOS os clubes por quesito ("Dados dos clubes" na comparacao). */
    @GetMapping("/ranking")
    public RankingDTO ranking(
            @RequestParam(defaultValue = "TODOS") String filtro,
            @RequestParam(defaultValue = "5") int limite,
            @RequestParam(required = false) Long clubeId,
            @RequestParam(required = false) Long campeonatoId) {
        // campeonatoId explicito tem prioridade; senao deriva do clube comparado
        if (campeonatoId != null) return rankingService.ranking(filtro, limite, campeonatoId);
        return rankingService.rankingPorClube(filtro, limite, clubeId);
    }

    /** Metadata dos campos (dirige a UI). */
    @GetMapping("/campos")
    public List<CampoMetaDTO> campos() {
        List<CampoMetaDTO> out = new ArrayList<>();
        for (StatFields.StatMeta m : StatFields.CAMPOS) {
            out.add(new CampoMetaDTO(m.getCampo(), m.getRotulo(), m.getCategoria(), m.getTipo().name()));
        }
        return out;
    }
}