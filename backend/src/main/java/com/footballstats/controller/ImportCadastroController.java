package com.footballstats.controller;

import com.footballstats.dto.ImportCadastroDTO;
import com.footballstats.service.ImportCadastroService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/cadastros")
public class ImportCadastroController {

    private final ImportCadastroService service;
    public ImportCadastroController(ImportCadastroService service) { this.service = service; }

    /** Upload de campeonato.txt: nacao;campeonato;clube1;clube2;... por linha. */
    @PostMapping("/importar")
    public ResponseEntity<ImportCadastroDTO> importar(@RequestParam("arquivo") MultipartFile arquivo) throws IOException {
        String conteudo = new String(arquivo.getBytes(), StandardCharsets.UTF_8);
        return ResponseEntity.ok(service.importar(conteudo));
    }
}
