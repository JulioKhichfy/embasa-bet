package com.footballstats.controller;

import com.footballstats.dto.FusaoResultDTO;
import com.footballstats.model.Clube;
import com.footballstats.service.ClubeService;
import com.footballstats.service.FusaoClubeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/clubes")
public class ClubeController {
    private final ClubeService service;
    private final FusaoClubeService fusaoService;

    public ClubeController(ClubeService service, FusaoClubeService fusaoService) {
        this.service = service;
        this.fusaoService = fusaoService;
    }

    @GetMapping public List<Clube> listar(@RequestParam(required = false) Long campeonatoId) {
        return campeonatoId != null ? service.porCampeonato(campeonatoId) : service.listar();
    }
    @GetMapping("/{id}") public Clube buscar(@PathVariable Long id) { return service.buscar(id); }
    @GetMapping("/{id}/partidas-count") public int contarPartidas(@PathVariable Long id) { return service.contarPartidas(id); }
    @PostMapping public Clube criar(@RequestParam Long campeonatoId, @RequestBody Clube c) { return service.salvar(campeonatoId, c); }
    @PutMapping("/{id}") public Clube atualizar(@PathVariable Long id, @RequestBody Clube c) { return service.atualizar(id, c); }
    @DeleteMapping("/{id}") public void excluir(@PathVariable Long id) { service.excluir(id); }

    /** Pares de clubes suspeitos de serem o mesmo (sugestao para a tela de fusao). */
    @GetMapping("/duplicados")
    public List<com.footballstats.dto.DuplicadoSugestaoDTO> duplicados(@RequestParam(required = false) Long campeonatoId) {
        return service.sugerirDuplicados(campeonatoId);
    }

    /**
     * Funde dois clubes duplicados. O clube 'manterId' permanece e absorve
     * todas as partidas de 'removerId', que e apagado.
     */
    @PostMapping("/fundir")
    public ResponseEntity<FusaoResultDTO> fundir(@RequestParam Long manterId, @RequestParam Long removerId) {
        FusaoResultDTO r = fusaoService.fundir(manterId, removerId);
        return r.ok ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
    }
}
