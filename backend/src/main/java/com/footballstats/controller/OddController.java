package com.footballstats.controller;

import com.footballstats.model.Odd;
import com.footballstats.repository.OddRepository;
import com.footballstats.service.OddImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Endpoints de odds.
 *
 * O laco de multiplos arquivos vive AQUI, nao no servico, para que o proxy
 * transacional do Spring seja respeitado por arquivo: um dump corrompido faz
 * rollback so dele, e os outros entram. Mesma decisao ja tomada no import em
 * lote do SofaScore.
 */
@RestController
@RequestMapping("/api/odds")
public class OddController {

    private final OddImportService importService;
    private final OddRepository oddRepo;

    public OddController(OddImportService importService, OddRepository oddRepo) {
        this.importService = importService;
        this.oddRepo = oddRepo;
    }

    /** Um dump da bet365. confrontoId opcional: sem ele, tentamos casar pelos clubes. */
    @PostMapping("/importar")
    public ResponseEntity<OddImportService.Relatorio> importar(
            @RequestParam("arquivo") MultipartFile arquivo,
            @RequestParam(value = "confrontoId", required = false) Long confrontoId) throws IOException {

        String html = new String(arquivo.getBytes(), StandardCharsets.UTF_8);
        OddImportService.Relatorio r = importService.importar(html, confrontoId);
        return r.sucesso ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
    }

    /** Varios dumps de uma vez, um por confronto. */
    @PostMapping("/importar-lote")
    public List<OddImportService.Relatorio> importarLote(
            @RequestParam("arquivos") MultipartFile[] arquivos) {

        List<OddImportService.Relatorio> saida = new ArrayList<>();
        for (MultipartFile f : arquivos) {
            try {
                String html = new String(f.getBytes(), StandardCharsets.UTF_8);
                saida.add(importService.importar(html, null));
            } catch (Exception e) {
                OddImportService.Relatorio r = new OddImportService.Relatorio();
                r.sucesso = false;
                r.mensagem = f.getOriginalFilename() + ": " + e.getMessage();
                saida.add(r);
            }
        }
        return saida;
    }

    /** Cotacoes mais recentes de um confronto (uma por mercado/linha/selecao). */
    @GetMapping("/confronto/{id}")
    public List<Odd> atuais(@PathVariable Long id) {
        return oddRepo.findAtuaisByConfronto(id);
    }

    /** Historico completo, para ver o movimento de linha. */
    @GetMapping("/confronto/{id}/historico")
    public List<Odd> historico(@PathVariable Long id) {
        return oddRepo.findByConfrontoId(id);
    }
}