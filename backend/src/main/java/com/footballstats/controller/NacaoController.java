package com.footballstats.controller;

import com.footballstats.model.Nacao;
import com.footballstats.service.NacaoService;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/nacoes")
public class NacaoController {
    private final NacaoService service;
    public NacaoController(NacaoService service) { this.service = service; }

    @GetMapping public List<Nacao> listar() { return service.listar(); }
    @GetMapping("/{id}") public Nacao buscar(@PathVariable Long id) { return service.buscar(id); }
    @PostMapping public Nacao criar(@RequestBody Nacao n) { return service.salvar(n); }
    @PutMapping("/{id}") public Nacao atualizar(@PathVariable Long id, @RequestBody Nacao n) { return service.atualizar(id, n); }
    @DeleteMapping("/{id}") public void excluir(@PathVariable Long id) { service.excluir(id); }
}
