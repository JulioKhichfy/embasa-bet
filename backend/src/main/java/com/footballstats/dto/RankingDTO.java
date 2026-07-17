package com.footballstats.dto;

import java.util.ArrayList;
import java.util.List;

/** Ranking dos clubes por quesito (soma e media), para o quadro "Dados dos clubes". */
public class RankingDTO {

    /** Uma entrada do ranking: um clube com seu total e media no quesito. */
    public static class Item {
        public Long clubeId;
        public String clubeNome;
        public String campeonatoNome;
        public int jogos;
        public float total;
        public float media;

        public Item() {}
        public Item(Long clubeId, String clubeNome, String campeonatoNome, int jogos, float total, float media) {
            this.clubeId = clubeId; this.clubeNome = clubeNome; this.campeonatoNome = campeonatoNome;
            this.jogos = jogos; this.total = total; this.media = media;
        }
    }

    /** Um quesito (ex.: "Cartões amarelos") com a lista ordenada de clubes. */
    public static class Quesito {
        public String chave;
        public String rotulo;
        public List<Item> itens = new ArrayList<>();

        public Quesito() {}
        public Quesito(String chave, String rotulo) { this.chave = chave; this.rotulo = rotulo; }
    }

    public String filtro;              // TODOS | CASA | FORA
    public int limite;                 // N partidas consideradas por clube
    public List<Quesito> quesitos = new ArrayList<>();
}