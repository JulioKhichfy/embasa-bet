package com.footballstats.controller;

import com.footballstats.probabilidade.MatrizPlacares;
import com.footballstats.service.BacktestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * O backtest e caro (segundos a minutos). Fica num endpoint proprio, chamado
 * sob demanda, nunca no caminho de geracao do bilhete.
 */
@RestController
@RequestMapping("/api/backtest")
public class BacktestController {

    private final BacktestService service;

    public BacktestController(BacktestService service) {
        this.service = service;
    }

    @PostMapping("/campeonato/{id}")
    public ResponseEntity<BacktestService.Relatorio> rodar(
            @PathVariable Long id,
            @RequestParam(value = "modelo", required = false) String modelo,
            @RequestParam(value = "aquecimento", required = false) Integer aquecimento,
            @RequestParam(value = "passoReajuste", required = false) Integer passo) {

        BacktestService.Relatorio r = service.rodar(
                id,
                modelo == null ? MatrizPlacares.Modelo.DIXON_COLES : MatrizPlacares.Modelo.valueOf(modelo),
                aquecimento == null ? 60 : aquecimento,
                passo == null ? 10 : passo);

        return r.sucesso ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
    }
}