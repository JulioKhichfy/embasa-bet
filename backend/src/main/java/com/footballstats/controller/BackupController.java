package com.footballstats.controller;

import com.footballstats.dto.ImportCadastroDTO;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Backup do banco H2:
 *  - GET  /api/backup/dump      -> baixa um .sql completo (SCRIPT TO)
 *  - POST /api/backup/restaurar -> sobe um .sql e executa (RUNSCRIPT)
 *  - POST /api/backup/limpar    -> APAGA TODOS OS DADOS (DROP ALL OBJECTS)
 *
 * ATENCAO: restaurar e limpar sao operacoes destrutivas e irreversiveis.
 * Ambas exigem confirmacao explicita do usuario no frontend.
 */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private final DataSource dataSource;
    public BackupController(DataSource dataSource) { this.dataSource = dataSource; }

    @GetMapping("/dump")
    public ResponseEntity<FileSystemResource> dump() throws Exception {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path out = Files.createTempFile("footballstats_backup_" + ts + "_", ".sql");

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            // SCRIPT TO grava o dump no caminho do servidor
            st.execute("SCRIPT TO '" + out.toAbsolutePath().toString().replace("\\", "/") + "'");
        }

        FileSystemResource resource = new FileSystemResource(out.toFile());
        String filename = "footballstats_backup_" + ts + ".sql";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(resource.contentLength())
                .body(resource);
    }

    /**
     * Restaura um dump .sql gerado por /dump.
     * O conteudo atual e descartado (DROP ALL OBJECTS) antes do RUNSCRIPT,
     * de modo que o banco fique exatamente igual ao do arquivo.
     */
    @PostMapping("/restaurar")
    public ResponseEntity<Map<String, Object>> restaurar(@RequestParam("arquivo") MultipartFile arquivo) {
        Map<String, Object> resp = new HashMap<>();
        Path tmp = null;
        try {
            String nome = arquivo.getOriginalFilename() != null ? arquivo.getOriginalFilename() : "backup.sql";
            if (!nome.toLowerCase().endsWith(".sql")) {
                resp.put("ok", false);
                resp.put("mensagem", "Arquivo inválido: envie um .sql gerado pelo botão de backup.");
                return ResponseEntity.badRequest().body(resp);
            }

            tmp = Files.createTempFile("footballstats_restore_", ".sql");
            Files.write(tmp, arquivo.getBytes());

            try (Connection conn = dataSource.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("DROP ALL OBJECTS");
                st.execute("RUNSCRIPT FROM '" + tmp.toAbsolutePath().toString().replace("\\", "/")
                        + "' CHARSET 'UTF-8'");
            }

            resp.put("ok", true);
            resp.put("mensagem", "Backup restaurado com sucesso. Recarregue a página para ver os dados.");
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            resp.put("ok", false);
            resp.put("mensagem", "Falha ao restaurar: " + e.getMessage());
            return ResponseEntity.internalServerError().body(resp);
        } finally {
            if (tmp != null) { try { Files.deleteIfExists(tmp); } catch (Exception ignored) { } }
        }
    }

    /**
     * APAGA TODOS OS DADOS do banco. Operacao irreversivel.
     * Exige o parametro confirmacao=APAGAR como trava adicional.
     */
    @PostMapping("/limpar")
    public ResponseEntity<Map<String, Object>> limpar(@RequestParam String confirmacao) {
        Map<String, Object> resp = new HashMap<>();
        if (!"APAGAR".equals(confirmacao)) {
            resp.put("ok", false);
            resp.put("mensagem", "Confirmação inválida. Operação cancelada.");
            return ResponseEntity.badRequest().body(resp);
        }
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            resp.put("ok", true);
            resp.put("mensagem", "Banco apagado. Reinicie o backend para recriar o schema vazio.");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            resp.put("ok", false);
            resp.put("mensagem", "Falha ao apagar: " + e.getMessage());
            return ResponseEntity.internalServerError().body(resp);
        }
    }
}