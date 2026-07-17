package com.footballstats.dto;

public class ImportResultDTO {
    public boolean importada;
    public String mensagem;
    public String nomeArquivo;
    public Long partidaId;
    public String clubeCasa;
    public String clubeFora;
    public Integer golsCasa;
    public Integer golsFora;
    public String data;

    public static ImportResultDTO ignorada(String msg) {
        ImportResultDTO d = new ImportResultDTO();
        d.importada = false; d.mensagem = msg; return d;
    }
}
