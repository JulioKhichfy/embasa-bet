package com.footballstats.dto;

import java.util.ArrayList;
import java.util.List;

public class ImportCadastroDTO {
    public int nacoesCriadas = 0;
    public int campeonatosCriados = 0;
    public int clubesCriados = 0;
    public int linhasProcessadas = 0;
    public int linhasIgnoradas = 0;
    public List<String> avisos = new ArrayList<>();
    public String mensagem;
}
