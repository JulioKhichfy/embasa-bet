package com.footballstats.dto;

import java.util.List;
import java.util.Map;

/**
 * Resposta da tela de detalhe do clube: ultimas N partidas + media aritmetica
 * de todos os itens, com filtro TODOS/CASA/FORA.
 */
public class ClubeDetalheDTO {
    public Long clubeId;
    public String clubeNome;
    public String filtro;         // TODOS | CASA | FORA
    public int totalPartidas;     // consideradas (apos limite)
    public List<PartidaResumoDTO> partidas;
    public Map<String, Float> medias;   // campo -> media (perspectiva do clube)
    public float mediaPontos;
    public float mediaGolsFeitos;
    public float mediaGolsSofridos;
}
