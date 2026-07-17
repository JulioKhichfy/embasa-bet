package com.footballstats.dto;

/** Par de clubes que provavelmente sao o mesmo, sugerido para fusao. */
public class DuplicadoSugestaoDTO {
    public Long clubeAId;
    public String clubeANome;
    public int partidasA;

    public Long clubeBId;
    public String clubeBNome;
    public int partidasB;

    public String campeonatoNome;
}
