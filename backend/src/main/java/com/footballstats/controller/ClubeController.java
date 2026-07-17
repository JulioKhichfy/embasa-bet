package com.footballstats.controller;

import com.footballstats.model.Clube;
import com.footballstats.service.ClubeService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/clubes")
public class ClubeController {
    private final ClubeService service;
    public ClubeController(ClubeService service) { this.service = service; }

    @GetMapping public List<Clube> listar(@RequestParam(required = false) Long campeonatoId) {
        return campeonatoId != null ? service.porCampeonato(campeonatoId) : service.listar();
    }
    @GetMapping("/{id}") public Clube buscar(@PathVariable Long id) { return service.buscar(id); }
    @GetMapping("/{id}/partidas-count") public int contarPartidas(@PathVariable Long id) { return service.contarPartidas(id); }
    @PostMapping public Clube criar(@RequestParam Long campeonatoId, @RequestBody Clube c) { return service.salvar(campeonatoId, c); }
    @PutMapping("/{id}") public Clube atualizar(@PathVariable Long id, @RequestBody Clube c) { return service.atualizar(id, c); }
    @DeleteMapping("/{id}") public void excluir(@PathVariable Long id) { service.excluir(id); }
}
