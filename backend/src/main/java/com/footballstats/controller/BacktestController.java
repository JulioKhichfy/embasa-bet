package com.footballstats.controller;

import com.footballstats.probabilidade.MatrizPlacares;
import com.footballstats.service.BacktestService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

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

        List<Long> lista = new ArrayList<>();
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

    /**
     * Varredura de hiperparâmetros fora da amostra.
     *
     * CARA: roda o backtest agregado inteiro uma vez por configuração. Com uma
     * grade 4x2 e 4 campeonatos, conte alguns minutos.
     *
     * Leia o campo `aviso` do resultado ANTES de escolher qualquer coisa: pegar
     * o máximo de uma grade ruidosa é vazamento de dados em câmera lenta.
     */
    @PostMapping("/varredura")
    public ResponseEntity<BacktestService.Varredura> varredura(
            @RequestParam("campeonatos") String ids,
            @RequestParam(value = "penalidades", required = false) String penalidades,
            @RequestParam(value = "decaimentos", required = false) String decaimentos,
            @RequestParam(value = "modelo", required = false) String modelo,
            @RequestParam(value = "aquecimento", required = false) Integer aquecimento,
            @RequestParam(value = "passoReajuste", required = false) Integer passo) {

        List<Long> lista = new ArrayList<>();
        for (String s : ids.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) lista.add(Long.valueOf(t));
        }
        BacktestService.Varredura v = service.varrer(
                lista,
                modelo == null ? MatrizPlacares.Modelo.DIXON_COLES : MatrizPlacares.Modelo.valueOf(modelo),
                aquecimento == null ? 60 : aquecimento,
                passo == null ? 15 : passo,
                parseGrade(penalidades, new double[] { 0, 3, 8, 16, 30 }),
                parseGrade(decaimentos, new double[] { 0, 0.005 }));

        return v.sucesso ? ResponseEntity.ok(v) : ResponseEntity.badRequest().body(v);
    }

    private double[] parseGrade(String csv, double[] padrao) {
        if (csv == null || csv.isBlank()) return padrao;
        String[] partes = csv.split(",");
        double[] out = new double[partes.length];
        for (int i = 0; i < partes.length; i++) out[i] = Double.parseDouble(partes[i].trim());
        return out;
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