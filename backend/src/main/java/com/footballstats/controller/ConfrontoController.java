package com.footballstats.controller;

import com.footballstats.model.Confronto;
import com.footballstats.repository.ConfrontoRepository;
import com.footballstats.service.ConfrontoImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Confrontos: os jogos que ainda vao acontecer.
 *
 * O laco do lote fica AQUI e nao no servico para que o proxy transacional do
 * Spring valha por arquivo: um dump corrompido faz rollback so dele e os outros
 * entram. Mesma decisao do import de partidas e do import de odds.
 */
@RestController
@RequestMapping("/api/confrontos")
public class ConfrontoController {

    private final ConfrontoImportService importService;
    private final ConfrontoRepository repo;

    public ConfrontoController(ConfrontoImportService importService, ConfrontoRepository repo) {
        this.importService = importService;
        this.repo = repo;
    }

    /** Jogos de uma data; sem parametro, os de hoje. */
    @GetMapping
    public List<Confronto> porData(@RequestParam(value = "data", required = false) String data) {
        LocalDate d = (data == null || data.isBlank()) ? LocalDate.now() : LocalDate.parse(data);
        return repo.findByDataOrderByHoraAsc(d);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Confronto> porId(@PathVariable Long id) {
        return repo.findById(id).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.badRequest().body(null));
    }

    /** Um dump de pagina de jogo do SofaScore. */
    @PostMapping("/importar")
    public ResponseEntity<ConfrontoImportService.Relatorio> importar(
            @RequestParam("arquivo") MultipartFile arquivo) throws Exception {

        String html = new String(arquivo.getBytes(), StandardCharsets.UTF_8);
        ConfrontoImportService.Relatorio r =
                importService.importar(html, arquivo.getOriginalFilename());
        return r.sucesso ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
    }

    /** Varios dumps: o fluxo normal de "os jogos de hoje". */
    @PostMapping("/importar-lote")
    public List<ConfrontoImportService.Relatorio> importarLote(
            @RequestParam("arquivos") MultipartFile[] arquivos) {

        List<ConfrontoImportService.Relatorio> saida = new ArrayList<>();
        for (MultipartFile f : arquivos) {
            try {
                String html = new String(f.getBytes(), StandardCharsets.UTF_8);
                saida.add(importService.importar(html, f.getOriginalFilename()));
            } catch (Exception e) {
                ConfrontoImportService.Relatorio r = new ConfrontoImportService.Relatorio();
                r.sucesso = false;
                r.arquivo = f.getOriginalFilename();
                r.mensagem = e.getMessage();
                saida.add(r);
            }
        }
        return saida;
    }

    @DeleteMapping("/{id}")
    public void remover(@PathVariable Long id) {
        repo.deleteById(id);
    }
}