package com.footballstats.dto;

import java.util.Map;

public class PartidaResumoDTO {
    public Long partidaId;
    public String data;
    public String adversario;
    public boolean emCasa;
    public int golsFeitos;
    public int golsSofridos;
    public String resultado;   // V | E | D
    public int pontos;
    /** Estatisticas da partida na perspectiva do clube (preenchido no detalhe/comparacao). */
    public Map<String, Float> estatisticas;
    /** Estatisticas do adversario na mesma partida (o outro lado casa/fora). */
    public Map<String, Float> estatisticasAdversario;
}