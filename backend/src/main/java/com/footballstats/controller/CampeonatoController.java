package com.footballstats.controller;

import com.footballstats.model.Campeonato;
import com.footballstats.service.CampeonatoService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/campeonatos")
public class CampeonatoController {
    private final CampeonatoService service;
    public CampeonatoController(CampeonatoService service) { this.service = service; }

    @GetMapping public List<Campeonato> listar(@RequestParam(required = false) Long nacaoId) {
        return nacaoId != null ? service.porNacao(nacaoId) : service.listar();
    }
    @GetMapping("/{id}") public Campeonato buscar(@PathVariable Long id) { return service.buscar(id); }
    @PostMapping public Campeonato criar(@RequestParam Long nacaoId, @RequestBody Campeonato c) { return service.salvar(nacaoId, c); }
    @PutMapping("/{id}") public Campeonato atualizar(@PathVariable Long id, @RequestBody Campeonato c) { return service.atualizar(id, c); }
    @DeleteMapping("/{id}") public void excluir(@PathVariable Long id) { service.excluir(id); }
}
