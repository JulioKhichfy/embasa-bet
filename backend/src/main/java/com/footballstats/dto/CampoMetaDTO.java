package com.footballstats.dto;

/** Metadata de um campo enviada ao frontend (dirige a renderizacao). */
public class CampoMetaDTO {
    public String campo;
    public String rotulo;
    public String categoria;
    public String tipo;
    public CampoMetaDTO() {}
    public CampoMetaDTO(String campo, String rotulo, String categoria, String tipo) {
        this.campo = campo; this.rotulo = rotulo; this.categoria = categoria; this.tipo = tipo;
    }
}
