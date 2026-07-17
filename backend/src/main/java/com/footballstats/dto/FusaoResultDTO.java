package com.footballstats.dto;

import java.util.ArrayList;
import java.util.List;

/** Resultado da fusão de dois clubes duplicados. */
public class FusaoResultDTO {
    public boolean ok;
    public String mensagem;

    public Long clubeMantidoId;
    public String clubeMantidoNome;
    public Long clubeRemovidoId;
    public String clubeRemovidoNome;

    public int partidasTransferidas;
    public int partidasDescartadas;   // duplicatas que já existiam no clube mantido
    public List<String> avisos = new ArrayList<>();

    public static FusaoResultDTO erro(String msg) {
        FusaoResultDTO r = new FusaoResultDTO();
        r.ok = false;
        r.mensagem = msg;
        return r;
    }
}
