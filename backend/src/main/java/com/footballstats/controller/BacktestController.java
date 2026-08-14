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

    /**
     * Backtest agregado: ajusta cada campeonato separadamente e SOMA a avaliacao.
     *
     * Com amostra curta por liga, e a unica forma de chegar num n com poder
     * estatistico sem esperar temporadas. Passe os ids separados por virgula.
     */
    @PostMapping("/agregado")
    public ResponseEntity<BacktestService.Relatorio> agregado(
            @RequestParam("campeonatos") String ids,
            @RequestParam(value = "modelo", required = false) String modelo,
            @RequestParam(value = "aquecimento", required = false) Integer aquecimento,
            @RequestParam(value = "passoReajuste", required = false) Integer passo) {

        java.util.List<Long> lista = new java.util.ArrayList<>();
        for (String s : ids.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) lista.add(Long.valueOf(t));
        }
        BacktestService.Relatorio r = service.rodarAgregado(
                lista,
                modelo == null ? MatrizPlacares.Modelo.DIXON_COLES : MatrizPlacares.Modelo.valueOf(modelo),
                aquecimento == null ? 60 : aquecimento,
                passo == null ? 10 : passo);
        return r.sucesso ? ResponseEntity.ok(r) : ResponseEntity.badRequest().body(r);
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