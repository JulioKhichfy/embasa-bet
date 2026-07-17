package com.footballstats.parser;

import com.footballstats.model.StatFields;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser do HTML do SofaScore.
 *
 * Estrategia (validada contra o partida.html de exemplo):
 *  - Cabecalho: nome dos clubes, placar e data.
 *  - Cada item de estatistica e um container com 3 <bdi> na ordem
 *    [valor_casa, NOME_DO_ITEM, valor_fora]. Localizamos o item pelo NOME
 *    (exatamente o no que os XPaths do sofascore.properties resolvem) e lemos
 *    o <bdi> de cima (casa) e o de baixo (fora).
 *  - Itens em razao (tipo RATIO: bolas longas, cruzamentos, dribles, duelos
 *    no chao/aereos, desarmes ganhos) nao usam 3 <bdi>; deles capturamos
 *    APENAS o percentual (ex.: "79%") de cada lado, como float.
 *  - Item ausente -> 0.0 para casa e fora.
 */
public class SofaScoreParser {

    public static class Resultado {
        public String nomeClubeCasa;
        public String nomeClubeFora;
        public int golsCasa;
        public int golsFora;
        public LocalDate data;
        public Map<String, Float> casa = new LinkedHashMap<>();
        public Map<String, Float> fora = new LinkedHashMap<>();
    }

    private static final Pattern NUM = Pattern.compile("-?\\d+(?:[.,]\\d+)?");
    private static final Pattern PCT = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*%");

    private final Properties props;

    public SofaScoreParser(Properties props) {
        this.props = props != null ? props : new Properties();
    }

    public Resultado parse(String html) {
        Document doc = Jsoup.parse(html);
        // versao W3C para avaliar os XPaths do cabecalho
        org.w3c.dom.Document w3c = null;
        try {
            w3c = new org.jsoup.helper.W3CDom().fromJsoup(doc);
        } catch (Exception ignored) { }

        Resultado r = new Resultado();
        extrairCabecalho(doc, w3c, r);
        extrairEstatisticas(doc, r);

        for (StatFields.StatMeta m : StatFields.CAMPOS) {
            r.casa.putIfAbsent(m.getCampo(), 0f);
            r.fora.putIfAbsent(m.getCampo(), 0f);
        }
        return r;
    }

    // ---------------------------------------------------------------------

    /** Avalia um XPath configurado no .properties sobre o DOM W3C; texto ou null. */
    private String xpathTexto(org.w3c.dom.Document w3c, String chave) {
        if (w3c == null) return null;
        String xp = props.getProperty(chave);
        if (xp == null || xp.isBlank()) return null;
        try {
            XPath xpath = XPathFactory.newInstance().newXPath();
            org.w3c.dom.Node node = (org.w3c.dom.Node) xpath.evaluate(xp, w3c, XPathConstants.NODE);
            return node != null ? node.getTextContent().trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void extrairCabecalho(Document doc, org.w3c.dom.Document w3c, Resultado r) {
        // 1) Tenta pelos XPaths configurados (fonte da verdade).
        String nomeCasa = xpathTexto(w3c, "nome_clube_casa");
        String nomeFora = xpathTexto(w3c, "nome_clube_fora");
        String resCasa  = xpathTexto(w3c, "resultado_clube_casa");
        String resFora  = xpathTexto(w3c, "resultado_clube_fora");
        String dataStr  = xpathTexto(w3c, "data_partida");

        // 2) Fallback: dois primeiros <bdi> = nomes.
        if (nomeCasa == null || nomeFora == null) {
            Elements bdis = doc.select("bdi");
            if (bdis.size() >= 2) {
                if (nomeCasa == null) nomeCasa = bdis.get(0).text().trim();
                if (nomeFora == null) nomeFora = bdis.get(1).text().trim();
            }
        }
        r.nomeClubeCasa = nomeCasa;
        r.nomeClubeFora = nomeFora;

        // 3) Placar.
        // Os XPaths resultado_clube_casa/fora sao caminhos posicionais frageis:
        // dependem da contagem exata de <div>/<span> aninhados, que muda conforme
        // a normalizacao do DOM (Jsoup reescreve a arvore), resolvendo para nós
        // vazios -> 0/0. Ancoramos por ESTRUTURA: o placar e o unico <span> cujos
        // filhos diretos sao [digito, "-", digito]. XPath so como fallback.
        int[] placar = placarPorEstrutura(doc);
        if (placar != null) {
            r.golsCasa = placar[0];
            r.golsFora = placar[1];
        } else {
            r.golsCasa = paraInt(resCasa);
            r.golsFora = paraInt(resFora);
        }

        // 4) Data: XPath -> senao primeiro dd/MM/yyyy do texto.
        LocalDate data = null;
        if (dataStr != null) data = tentarData(dataStr);
        if (data == null) {
            Matcher md = Pattern.compile("(\\d{2})/(\\d{2})/(\\d{4})").matcher(doc.text());
            if (md.find()) data = tentarData(md.group(0));
        }
        r.data = data != null ? data : LocalDate.now();
    }

    /**
     * Placar via estrutura: procura o <span> cujos 3 filhos diretos sao
     * [digito, "-", digito]. Retorna [golsCasa, golsFora] ou null.
     */
    private int[] placarPorEstrutura(Document doc) {
        for (Element span : doc.select("span")) {
            Elements filhos = span.children();
            if (filhos.size() == 3) {
                String a = filhos.get(0).text().trim();
                String sep = filhos.get(1).text().trim();
                String b = filhos.get(2).text().trim();
                if (a.matches("\\d{1,2}") && (sep.equals("-") || sep.equals("–") || sep.equals(":")) && b.matches("\\d{1,2}")) {
                    return new int[]{ Integer.parseInt(a), Integer.parseInt(b) };
                }
            }
        }
        return null;
    }

    private LocalDate tentarData(String s) {
        Matcher md = Pattern.compile("(\\d{2})/(\\d{2})/(\\d{4})").matcher(s);
        if (md.find()) {
            try {
                return LocalDate.parse(md.group(0), DateTimeFormatter.ofPattern("dd/MM/yyyy"));
            } catch (Exception ignored) { }
        }
        return null;
    }

    private int paraInt(String s) {
        if (s == null) return 0;
        Matcher m = Pattern.compile("\\d+").matcher(s);
        return m.find() ? Integer.parseInt(m.group()) : 0;
    }

    private void extrairEstatisticas(Document doc, Resultado r) {
        for (StatFields.StatMeta m : StatFields.CAMPOS) {
            switch (m.getTipo()) {
                case RATIO -> extrairRatio(doc, m, r);
                default    -> extrairPadrao(doc, m, r);
            }
        }
    }

    /** Item padrao: container com 3 <bdi> [casa, nome, fora]. */
    private void extrairPadrao(Document doc, StatFields.StatMeta m, Resultado r) {
        Element nomeBdi = acharBdiPorTexto(doc, m.getNomeHtml());
        if (nomeBdi == null) return; // ausente -> fica 0

        // sobe ate o menor container que contenha exatamente 3 <bdi>
        Element cont = nomeBdi;
        for (int i = 0; i < 8 && cont != null; i++) {
            Elements b = cont.select("bdi");
            if (b.size() == 3) {
                float casa = paraFloat(b.get(0).text(), m.getTipo());
                float fora = paraFloat(b.get(2).text(), m.getTipo());
                r.casa.put(m.getCampo(), casa);
                r.fora.put(m.getCampo(), fora);
                return;
            }
            cont = cont.parent();
        }
    }

    /**
     * Item em razao: layout [ratio_casa, pct_casa%, LABEL, pct_fora%, ratio_fora].
     * Capturamos apenas os percentuais (float).
     */
    private void extrairRatio(Document doc, StatFields.StatMeta m, Resultado r) {
        Element label = acharElementoPorTextoExato(doc, m.getNomeHtml());
        if (label == null) return;

        // sobe ate um container cujo texto tenha 2 percentuais
        Element cont = label;
        for (int i = 0; i < 6 && cont != null; i++) {
            cont = cont.parent();
            if (cont == null) break;
            // coleta percentuais na ordem dos nos-folha
            java.util.List<Float> pcts = new java.util.ArrayList<>();
            for (Element leaf : cont.getAllElements()) {
                if (leaf.children().isEmpty()) {
                    Matcher mm = PCT.matcher(leaf.ownText());
                    if (mm.find()) pcts.add(Float.parseFloat(mm.group(1).replace(',', '.')));
                }
            }
            if (pcts.size() >= 2) {
                r.casa.put(m.getCampo(), pcts.get(0));
                r.fora.put(m.getCampo(), pcts.get(pcts.size() - 1));
                return;
            }
        }
    }

    // ---------------------------------------------------------------------

    /** <bdi> cujo texto == alvo (normalizando acentos/caixa). */
    private Element acharBdiPorTexto(Document doc, String alvo) {
        for (Element b : doc.select("bdi")) {
            if (igual(b.text(), alvo)) return b;
        }
        return null;
    }

    /** Qualquer elemento-folha cujo ownText == alvo. */
    private Element acharElementoPorTextoExato(Document doc, String alvo) {
        for (Element e : doc.getAllElements()) {
            if (igual(e.ownText(), alvo)) return e;
        }
        return null;
    }

    private boolean igual(String a, String b) {
        if (a == null || b == null) return false;
        return normalizar(a).equals(normalizar(b));
    }

    private String normalizar(String s) {
        return s.trim().toLowerCase()
                .replaceAll("\\s+", " ");
    }

    /** Converte texto -> float conforme o tipo (remove %, km, etc.). */
    private float paraFloat(String txt, StatFields.Tipo tipo) {
        if (txt == null) return 0f;
        String t = txt.replace(',', '.');
        Matcher mm = NUM.matcher(t);
        if (mm.find()) {
            try { return Float.parseFloat(mm.group()); } catch (NumberFormatException e) { return 0f; }
        }
        return 0f;
    }
}
