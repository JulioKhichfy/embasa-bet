package com.footballstats.dto;

import java.util.ArrayList;
import java.util.List;

/** Resultado do upload de vários arquivos partida_N.html de uma vez. */
public class ImportLoteDTO {
    public int total = 0;
    public int importadas = 0;
    public int ignoradas = 0;
    public int comErro = 0;
    public List<ImportResultDTO> resultados = new ArrayList<>();
    public String mensagem;
}
