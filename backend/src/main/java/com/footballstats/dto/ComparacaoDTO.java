package com.footballstats.dto;

import java.util.List;
import java.util.Map;

/** Comparacao entre 2 clubes: medias lado a lado por item. */
public class ComparacaoDTO {
    public Long clubeAId;
    public String clubeANome;
    public Long clubeBId;
    public String clubeBNome;
    public String filtro;
    public int nPartidas;
    public Map<String, Float> mediasA;
    public Map<String, Float> mediasB;
    public List<CampoMetaDTO> campos;
}
