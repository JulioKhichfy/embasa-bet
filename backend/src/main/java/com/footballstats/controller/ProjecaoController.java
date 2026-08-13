package com.footballstats.controller;

import com.footballstats.model.Projecao;
import com.footballstats.probabilidade.MatrizPlacares;
import com.footballstats.repository.ProjecaoRepository;
import com.footballstats.service.ProjecaoService;
import com.footballstats.service.ValorService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/projecoes")
public class ProjecaoController {

    private final ProjecaoService projecaoService;
    private final ValorService valorService;
    private final ProjecaoRepository repo;

    public ProjecaoController(ProjecaoService projecaoService, ValorService valorService,
                              ProjecaoRepository repo) {
        this.projecaoService = projecaoService;
        this.valorService = valorService;
        this.repo = repo;
    }

    /** Gera (e grava) as projeções de um confronto. */
    @PostMapping("/confronto/{id}")
    public ResponseEntity<ProjecaoService.Resultado> gerar(
            @PathVariable Long id,
            @RequestParam(value = "modelo", required = false) String modelo) {

        MatrizPlacares.Modelo m = modelo == null ? MatrizPlacares.Modelo.DIXON_COLES
                : MatrizPlacares.Modelo.valueOf(modelo);
        ProjecaoService.Resultado r = projecaoService.projetar(id, m);
        return r.sucesso ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
    }

    @GetMapping("/confronto/{id}")
    public List<Projecao> listar(@PathVariable Long id) {
        return repo.findByConfrontoId(id);
    }

    /**
     * O bilhete: apostas com valor, ordenadas por EV.
     *
     * evMinimo default 5% -- abaixo disso o EV cabe dentro do erro do modelo e
     * a "vantagem" e provavelmente ruido.
     */
    @GetMapping("/confronto/{id}/valor")
    public List<ValorService.Aposta> valor(
            @PathVariable Long id,
            @RequestParam(value = "evMinimo", required = false) Double evMinimo,
            @RequestParam(value = "fracaoKelly", required = false) Double fracaoKelly) {

        return valorService.avaliar(id,
                evMinimo == null ? 0.05 : evMinimo,
                fracaoKelly == null ? 0.25 : fracaoKelly);
    }

    /** Descarta o ajuste em cache de um campeonato (após importar partidas). */
    @PostMapping("/invalidar/{campeonatoId}")
    public void invalidar(@PathVariable Long campeonatoId) {
        projecaoService.invalidar(campeonatoId);
    }
}