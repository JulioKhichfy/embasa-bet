package com.footballstats.model;

import lombok.*;

import java.util.List;

/**
 * Registro central de metadados de cada item de estatistica.
 * Um unico array dirige: (1) o parser (nome do item no HTML + chave no .properties),
 * (2) a serializacao, (3) a tela de comparacao no frontend.
 *
 * tipo:
 *   VALOR   -> item padrao com 3 <bdi> [casa, nome, fora]. Valor numerico direto.
 *   PERCENT -> item com sufixo % (ex.: posse de bola). Remove '%'.
 *   KM      -> item com sufixo km (ex.: distancia). Remove 'km'.
 *   RATIO   -> item em razao (ex.: 27/34 79%). Captura apenas o PERCENTUAL como float.
 */
public class StatFields {

    public enum Tipo { VALOR, PERCENT, KM, RATIO }

    @Getter @AllArgsConstructor
    public static class StatMeta {
        /** nome do campo na entidade/JSON (sem sufixo _casa/_fora) */
        private final String campo;
        /** rotulo exibido na UI */
        private final String rotulo;
        /** nome EXATO do item como aparece no <bdi> central do HTML do SofaScore */
        private final String nomeHtml;
        /** chave correspondente no sofascore.properties */
        private final String chaveProperties;
        /** categoria para agrupar na UI */
        private final String categoria;
        /** como converter o texto capturado */
        private final Tipo tipo;
    }

    public static final List<StatMeta> CAMPOS = List.of(
        // ---------------- Visao Geral ----------------
        new StatMeta("posseDeBola",            "Posse de bola",             "Posse de bola",             "sofascore.posse.de.bola",             "Visão Geral", Tipo.PERCENT),
        new StatMeta("golsEsperados",          "Gols esperados (xG)",       "Gols esperados (xG)",       "sofascore.gols.esperados",            "Visão Geral", Tipo.VALOR),
        new StatMeta("distanciaPercorrida",    "Distância percorrida",      "Distância percorrida",      "sofascore.distancia.percorrida",      "Visão Geral", Tipo.KM),
        new StatMeta("grandesChances",         "Grandes chances",           "Grandes chances",           "sofascore.grandes.chances",           "Visão Geral", Tipo.VALOR),
        new StatMeta("finalizacoes",           "Finalizações",              "Finalizações",              "sofascore.finalizacoes",              "Visão Geral", Tipo.VALOR),
        new StatMeta("defesasDoGoleiro",       "Defesas do goleiro",        "Defesas do goleiro",        "sofascore.defesas.do.goleiro",        "Visão Geral", Tipo.VALOR),
        new StatMeta("numeroDeSprints",        "Número de sprints",         "Número de sprints",         "sofascore.numero.de.sprints",         "Visão Geral", Tipo.VALOR),
        new StatMeta("escanteios",             "Escanteios",                "Escanteios",                "sofascore.escanteios",                "Visão Geral", Tipo.VALOR),
        new StatMeta("faltas",                 "Faltas",                    "Faltas",                    "sofascore.faltas",                    "Visão Geral", Tipo.VALOR),
        new StatMeta("passes",                 "Passes",                    "Passes",                    "sofascore.passes",                    "Visão Geral", Tipo.VALOR),
        new StatMeta("desarmes",               "Desarmes",                  "Desarmes",                  "sofascore.desarmes",                  "Visão Geral", Tipo.VALOR),
        new StatMeta("cartoesAmarelos",        "Cartões amarelos",          "Cartões amarelos",          "sofascore.cartoes.amarelos",          "Visão Geral", Tipo.VALOR),
        new StatMeta("cartoesVermelhos",       "Cartões vermelhos",         "Cartões vermelhos",         "sofascore.cartoes.vermelhos",         "Visão Geral", Tipo.VALOR),

        // ---------------- Finalizacoes ----------------
        new StatMeta("finalizacoesNoGol",      "Finalizações no gol",       "Finalizações no gol",       "sofascore.finalizacoes.no.gol",       "Finalizações", Tipo.VALOR),
        new StatMeta("finalizacoesNaTrave",    "Finalizações na trave",     "Finalizações na trave",     "sofascore.finalizacoes.na.trave",     "Finalizações", Tipo.VALOR),
        new StatMeta("finalizacoesParaFora",   "Finalizações para fora",    "Finalizações para fora",    "sofascore.finalizacoes.para.fora",    "Finalizações", Tipo.VALOR),
        new StatMeta("chutesDefendidos",       "Chutes defendidos",         "Chutes defendidos",         "sofascore.chutes.defendidos",         "Finalizações", Tipo.VALOR),
        new StatMeta("finalizacoesDentroArea", "Finalizações de dentro da área", "Finalizações de dentro da área", "sofascore.finalizacoes.de.dentro.da.area", "Finalizações", Tipo.VALOR),
        new StatMeta("finalizacoesForaArea",   "Finalizações de fora da área",   "Finalizações de fora da área",   "sofascore.finalizacoes.de.fora.da.area",   "Finalizações", Tipo.VALOR),

        // ---------------- Ataque ----------------
        new StatMeta("grandesChancesMarcadas", "Grandes chances marcadas",  "Grandes chances marcadas",  "sofascore.grandes.chances.marcadas",  "Ataque", Tipo.VALOR),
        new StatMeta("grandesChancesPerdidas", "Grandes chances perdidas",  "Grandes chances perdidas",  "sofascore.grandes.chances.perdidas",  "Ataque", Tipo.VALOR),
        new StatMeta("passeEmProfundidade",    "Passe em profundidade",     "Passe em profundidade",     "sofascore.passe.em.profundidade",     "Ataque", Tipo.VALOR),
        new StatMeta("acoesBolaAreaAdversaria","Ações com a bola na área adversária", "Ações com a bola na área adversária", "sofascore.acoes.com.a.bola.na.area.adversaria", "Ataque", Tipo.VALOR),
        new StatMeta("faltasSofridasTercoFinal","Faltas sofridas no terço final", "Faltas sofridas no terço final", "sofascore.faltas.sofridas.no.terco.final", "Ataque", Tipo.VALOR),
        new StatMeta("impedimentos",           "Impedimentos",              "Impedimentos",              "sofascore.impedimentos",              "Ataque", Tipo.VALOR),

        // ---------------- Passes ----------------
        new StatMeta("passesCertos",           "Passes certos",             "Passes certos",             "sofascore.passes.certos",             "Passes", Tipo.VALOR),
        new StatMeta("laterais",               "Laterais",                  "Laterais",                  "sofascore.laterais",                  "Passes", Tipo.VALOR),
        new StatMeta("entradasTercoFinal",     "Entradas no terço final",   "Entradas no terço final",   "sofascore.entradas.no.terco.final",   "Passes", Tipo.VALOR),
        new StatMeta("passesTercoFinal",       "Passes no terço final",     "Passes no terço final",     "sofascore.passes.no.terco.final",     "Passes", Tipo.VALOR),
        new StatMeta("bolasLongas",            "Bolas longas (%)",          "Bolas longas",              "sofascore.bolas.longas",              "Passes", Tipo.RATIO),
        new StatMeta("cruzamentos",            "Cruzamentos (%)",           "Cruzamentos",               "sofascore.cruzamentos",               "Passes", Tipo.RATIO),

        // ---------------- Duelos ----------------
        new StatMeta("duelos",                 "Duelos (%)",                "Duelos",                    "sofascore.duelos",                    "Duelos", Tipo.PERCENT),
        new StatMeta("perdasDeBola",           "Perdas de bola",            "Perdas de bola",            "sofascore.perdas.de.bola",            "Duelos", Tipo.VALOR),
        new StatMeta("duelosNoChao",           "Duelos no chão (%)",        "Duelos no chão",            "sofascore.duelos.no.chao",            "Duelos", Tipo.RATIO),
        new StatMeta("duelosAereos",           "Duelos aéreos (%)",         "Duelos aéreos",             "sofascore.duelos.aereos",             "Duelos", Tipo.RATIO),
        new StatMeta("dribles",                "Dribles (%)",               "Dribles",                   "sofascore.dribles",                   "Duelos", Tipo.RATIO),

        // ---------------- Defendendo ----------------
        new StatMeta("desarmesGanhos",         "Desarmes ganhos (%)",       "Desarmes ganhos",           "sofascore.desarmes.ganhos",           "Defendendo", Tipo.RATIO),
        new StatMeta("totalDeDesarmes",        "Total de desarmes",         "Total de desarmes",         "sofascore.total.de.desarmes",         "Defendendo", Tipo.VALOR),
        new StatMeta("interceptacoes",         "Interceptações",            "Interceptações",            "sofascore.interceptacoes",            "Defendendo", Tipo.VALOR),
        new StatMeta("recuperacoesDeBola",     "Recuperações de bola",      "Recuperações de bola",      "sofascore.recuperacoes.de.bola",      "Defendendo", Tipo.VALOR),
        new StatMeta("cortes",                 "Cortes",                    "Cortes",                    "sofascore.cortes",                    "Defendendo", Tipo.VALOR),
        new StatMeta("errosLevaramFinalizacao","Erros que levaram à finalização", "Erros que levaram à finalização", "sofascore.erros.que.levaram.a.finalizacao", "Defendendo", Tipo.VALOR),

        // ---------------- Goleiro ----------------
        new StatMeta("golsEvitados",           "Gols evitados",             "Gols evitados",             "sofascore.gols.evitados",             "Goleiro", Tipo.VALOR),
        new StatMeta("grandesDefesas",         "Grandes defesas",           "Grandes defesas",           "sofascore.grandes.defesas",           "Goleiro", Tipo.VALOR),
        new StatMeta("socos",                  "Socos",                     "Socos",                     "sofascore.socos",                     "Goleiro", Tipo.VALOR),
        new StatMeta("tirosDeMeta",            "Tiros de meta",             "Tiros de meta",             "sofascore.tiros.de.meta",             "Goleiro", Tipo.VALOR)
    );
}
