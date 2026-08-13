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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser do HTML do SofaScore.
 *
 * Estrategia (validada contra o partida.html de exemplo):
 *  - Cabecalho: nome dos clubes, placar, data, placar do intervalo e arbitro.
 *  - Cada item de estatistica e um container com 3 <bdi> na ordem
 *    [valor_casa, NOME_DO_ITEM, valor_fora]. Localizamos o item pelo NOME.
 *  - Itens em razao (tipo RATIO) nao usam 3 <bdi>; deles capturamos APENAS o
 *    percentual de cada lado, como float.
 *  - Item ausente -> 0.0 para casa e fora.
 *
 * PERIODOS
 * --------
 * O SofaScore separa as estatisticas em paineis por periodo, com ids estaveis:
 * #tabpanel-ALL, #tabpanel-1ST e #tabpanel-2ND. A versao anterior varria o
 * documento inteiro e pegava o primeiro item que casasse pelo nome -- ou seja,
 * sempre o painel ALL, e por acidente. Agora cada painel e extraido no seu
 * proprio escopo, o que da escanteios, cartoes e chutes POR TEMPO.
 *
 * Paineis 1ST/2ND so existem se estiverem montados no DOM no momento do dump.
 * Frameworks de aba costumam montar apenas o painel ativo, entao a extensao
 * precisa clicar em cada aba antes de salvar. Quando o painel nao vem, os mapas
 * do periodo ficam VAZIOS -- ausencia significa "nao coletado", nunca "zero".
 */
public class SofaScoreParser {

    public static class Resultado {
        public String nomeClubeCasa;
        public String nomeClubeFora;
        public int golsCasa;
        public int golsFora;

        /** Placar ao intervalo. null = nao extraido (nao confundir com 0). */
        public Integer golsCasa1T;
        public Integer golsFora1T;

        public LocalDate data;
        public String arbitro;

        public Map<String, Float> casa   = new LinkedHashMap<>();
        public Map<String, Float> fora   = new LinkedHashMap<>();
        public Map<String, Float> casa1T = new LinkedHashMap<>();
        public Map<String, Float> fora1T = new LinkedHashMap<>();
        public Map<String, Float> casa2T = new LinkedHashMap<>();
        public Map<String, Float> fora2T = new LinkedHashMap<>();

        /** Diagnostico do que o HTML realmente continha; util no relatorio de import. */
        public final List<String> avisos = new ArrayList<>();

        public boolean tem1T() { return !casa1T.isEmpty() && !fora1T.isEmpty(); }
        public boolean tem2T() { return !casa2T.isEmpty() && !fora2T.isEmpty(); }
    }

    private static final Pattern NUM    = Pattern.compile("-?\\d+(?:[.,]\\d+)?");
    private static final Pattern PCT    = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*%");
    private static final Pattern PLACAR = Pattern.compile("(\\d{1,2})\\s*[-–:]\\s*(\\d{1,2})");
    private static final Pattern ROTULO_1T = Pattern.compile(
            "^(1[oº°]?\\s*tempo|primeiro\\s*tempo|intervalo|1st\\s*half|halftime|ht)$");
    private static final Pattern ROTULO_ARBITRO = Pattern.compile(
            "^(arbitro|referee|juiz)\\s*:?$");

    private final Properties props;

    public SofaScoreParser(Properties props) {
        this.props = props != null ? props : new Properties();
    }

    public Resultado parse(String html) {
        Document doc = Jsoup.parse(html);
        org.w3c.dom.Document w3c = null;
        try {
            w3c = new org.jsoup.helper.W3CDom().fromJsoup(doc);
        } catch (Exception ignored) { }

        Resultado r = new Resultado();
        extrairCabecalho(doc, w3c, r);
        extrairTodosOsPeriodos(doc, r);

        // Partida inteira: ausente == zero.
        for (StatFields.StatMeta m : StatFields.CAMPOS) {
            r.casa.putIfAbsent(m.getCampo(), 0f);
            r.fora.putIfAbsent(m.getCampo(), 0f);
        }
        // Periodos: so completa se o painel existiu.
        completarSeColetado(r.casa1T, r.fora1T);
        completarSeColetado(r.casa2T, r.fora2T);

        return r;
    }

    private void completarSeColetado(Map<String, Float> casa, Map<String, Float> fora) {
        if (casa.isEmpty() && fora.isEmpty()) return;
        for (StatFields.StatMeta m : StatFields.CAMPOS) {
            casa.putIfAbsent(m.getCampo(), 0f);
            fora.putIfAbsent(m.getCampo(), 0f);
        }
    }

    // ---------------------------------------------------------------------
    // Cabecalho
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
        String nomeCasa = xpathTexto(w3c, "nome_clube_casa");
        String nomeFora = xpathTexto(w3c, "nome_clube_fora");
        String resCasa  = xpathTexto(w3c, "resultado_clube_casa");
        String resFora  = xpathTexto(w3c, "resultado_clube_fora");
        String dataStr  = xpathTexto(w3c, "data_partida");

        // Fallback: dois primeiros <bdi> = nomes.
        if (nomeCasa == null || nomeFora == null) {
            Elements bdis = doc.select("bdi");
            if (bdis.size() >= 2) {
                if (nomeCasa == null) nomeCasa = bdis.get(0).text().trim();
                if (nomeFora == null) nomeFora = bdis.get(1).text().trim();
            }
        }
        r.nomeClubeCasa = nomeCasa;
        r.nomeClubeFora = nomeFora;

        // Placar final. Os XPaths posicionais sao frageis (o Jsoup reescreve a
        // arvore e eles resolvem para nos vazios). Ancoramos por ESTRUTURA: o
        // placar e o unico <span> cujos filhos diretos sao [digito, "-", digito].
        Element spanPlacar = spanDoPlacar(doc);
        if (spanPlacar != null) {
            Elements f = spanPlacar.children();
            r.golsCasa = Integer.parseInt(f.get(0).text().trim());
            r.golsFora = Integer.parseInt(f.get(2).text().trim());
        } else {
            r.golsCasa = paraInt(resCasa);
            r.golsFora = paraInt(resFora);
            r.avisos.add("Placar final extraido por XPath (fallback frágil).");
        }

        extrairPlacar1T(doc, spanPlacar, r);
        extrairArbitro(doc, r);

        LocalDate data = null;
        if (dataStr != null) data = tentarData(dataStr);
        if (data == null) {
            Matcher md = Pattern.compile("(\\d{2})/(\\d{2})/(\\d{4})").matcher(doc.text());
            if (md.find()) data = tentarData(md.group(0));
        }
        r.data = data != null ? data : LocalDate.now();
    }

    /** O <span> cujos 3 filhos diretos sao [digito, separador, digito]. */
    private Element spanDoPlacar(Document doc) {
        for (Element span : doc.select("span")) {
            Elements filhos = span.children();
            if (filhos.size() == 3) {
                String a = filhos.get(0).text().trim();
                String sep = filhos.get(1).text().trim();
                String b = filhos.get(2).text().trim();
                if (a.matches("\\d{1,2}") && (sep.equals("-") || sep.equals("–") || sep.equals(":"))
                        && b.matches("\\d{1,2}")) {
                    return span;
                }
            }
        }
        return null;
    }

    /**
     * Placar do intervalo.
     *
     * Procura um rotulo de primeiro tempo e, subindo poucos niveis, um "N - M"
     * proximo. Como esse tipo de busca por vizinhanca erra facil, o resultado so
     * e aceito se passar em tres guardas:
     *
     *   1) nao pode ser o proprio <span> do placar final;
     *   2) gols do 1T de cada lado <= gols finais daquele lado (impossivel
     *      desmarcar gol);
     *   3) o par nao pode ser identico ao placar final quando o final tem gols
     *      -- seria quase certamente o placar final capturado por engano.
     *
     * A guarda 3 descarta o caso legitimo em que nenhum gol saiu no 2o tempo.
     * E uma troca deliberada: perder amostra e barato, contaminar a base com
     * placar de intervalo errado nao e.
     */
    private void extrairPlacar1T(Document doc, Element spanPlacarFinal, Resultado r) {
        for (Element e : doc.getAllElements()) {
            if (!e.children().isEmpty()) continue;
            if (!ROTULO_1T.matcher(normalizar(e.ownText())).matches()) continue;

            Element cont = e;
            for (int i = 0; i < 5 && cont != null; i++) {
                cont = cont.parent();
                if (cont == null || cont == spanPlacarFinal) break;

                Matcher mm = PLACAR.matcher(cont.text());
                while (mm.find()) {
                    int c = Integer.parseInt(mm.group(1));
                    int f = Integer.parseInt(mm.group(2));
                    if (c > r.golsCasa || f > r.golsFora) continue;              // guarda 2
                    if ((r.golsCasa + r.golsFora) > 0
                            && c == r.golsCasa && f == r.golsFora) continue;     // guarda 3
                    r.golsCasa1T = c;
                    r.golsFora1T = f;
                    return;
                }
            }
        }
        r.avisos.add("Placar do 1º tempo não encontrado; mercados por tempo ficam sem esta partida.");
    }

    /** Nome do arbitro: rotulo "Árbitro"/"Referee" e o texto vizinho. */
    private void extrairArbitro(Document doc, Resultado r) {
        for (Element e : doc.getAllElements()) {
            if (!e.children().isEmpty()) continue;
            if (!ROTULO_ARBITRO.matcher(normalizar(e.ownText())).matches()) continue;

            Element pai = e.parent();
            if (pai == null) continue;
            for (Element irmao : pai.getAllElements()) {
                if (irmao == e || !irmao.children().isEmpty()) continue;
                String txt = irmao.ownText().trim();
                if (txt.length() >= 3 && txt.length() <= 60
                        && !ROTULO_ARBITRO.matcher(normalizar(txt)).matches()
                        && !txt.matches(".*\\d.*")) {
                    r.arbitro = txt;
                    return;
                }
            }
        }
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

    // ---------------------------------------------------------------------
    // Estatisticas, por periodo
    // ---------------------------------------------------------------------

    private void extrairTodosOsPeriodos(Document doc, Resultado r) {
        Element painelAll = doc.selectFirst("#tabpanel-ALL");
        if (painelAll != null) {
            extrairEstatisticas(painelAll, r.casa, r.fora);
        } else {
            // HTML antigo ou layout diferente: varre o documento inteiro.
            extrairEstatisticas(doc, r.casa, r.fora);
            r.avisos.add("#tabpanel-ALL ausente; estatísticas extraídas do documento inteiro.");
        }

        Element painel1T = doc.selectFirst("#tabpanel-1ST");
        if (painel1T != null) {
            extrairEstatisticas(painel1T, r.casa1T, r.fora1T);
        } else {
            r.avisos.add("#tabpanel-1ST ausente; a extensão precisa abrir a aba do 1º tempo antes de salvar.");
        }

        Element painel2T = doc.selectFirst("#tabpanel-2ND");
        if (painel2T != null) {
            extrairEstatisticas(painel2T, r.casa2T, r.fora2T);
        } else {
            r.avisos.add("#tabpanel-2ND ausente; a extensão precisa abrir a aba do 2º tempo antes de salvar.");
        }
    }

    private void extrairEstatisticas(Element escopo, Map<String, Float> casa, Map<String, Float> fora) {
        for (StatFields.StatMeta m : StatFields.CAMPOS) {
            switch (m.getTipo()) {
                case RATIO -> extrairRatio(escopo, m, casa, fora);
                default    -> extrairPadrao(escopo, m, casa, fora);
            }
        }
    }

    /** Item padrao: container com 3 <bdi> [casa, nome, fora]. */
    private void extrairPadrao(Element escopo, StatFields.StatMeta m,
                               Map<String, Float> casa, Map<String, Float> fora) {
        Element nomeBdi = acharBdiPorTexto(escopo, m.getNomeHtml());
        if (nomeBdi == null) return; // ausente -> fica 0

        Element cont = nomeBdi;
        for (int i = 0; i < 8 && cont != null; i++) {
            Elements b = cont.select("bdi");
            if (b.size() == 3) {
                casa.put(m.getCampo(), paraFloat(b.get(0).text(), m.getTipo()));
                fora.put(m.getCampo(), paraFloat(b.get(2).text(), m.getTipo()));
                return;
            }
            cont = cont.parent();
            if (cont != null && cont == escopo.parent()) break; // nao escapa do painel
        }
    }

    /**
     * Item em razao: layout [ratio_casa, pct_casa%, LABEL, pct_fora%, ratio_fora].
     * Capturamos apenas os percentuais (float).
     */
    private void extrairRatio(Element escopo, StatFields.StatMeta m,
                              Map<String, Float> casa, Map<String, Float> fora) {
        Element label = acharElementoPorTextoExato(escopo, m.getNomeHtml());
        if (label == null) return;

        Element cont = label;
        for (int i = 0; i < 6 && cont != null; i++) {
            cont = cont.parent();
            if (cont == null) break;
            List<Float> pcts = new ArrayList<>();
            for (Element leaf : cont.getAllElements()) {
                if (leaf.children().isEmpty()) {
                    Matcher mm = PCT.matcher(leaf.ownText());
                    if (mm.find()) pcts.add(Float.parseFloat(mm.group(1).replace(',', '.')));
                }
            }
            if (pcts.size() >= 2) {
                casa.put(m.getCampo(), pcts.get(0));
                fora.put(m.getCampo(), pcts.get(pcts.size() - 1));
                return;
            }
            if (cont == escopo) break;
        }
    }

    // ---------------------------------------------------------------------

    /** <bdi> dentro do escopo cujo texto == alvo (normalizando caixa/espacos). */
    private Element acharBdiPorTexto(Element escopo, String alvo) {
        for (Element b : escopo.select("bdi")) {
            if (igual(b.text(), alvo)) return b;
        }
        return null;
    }

    /** Qualquer elemento-folha dentro do escopo cujo ownText == alvo. */
    private Element acharElementoPorTextoExato(Element escopo, String alvo) {
        for (Element e : escopo.getAllElements()) {
            if (igual(e.ownText(), alvo)) return e;
        }
        return null;
    }

    private boolean igual(String a, String b) {
        if (a == null || b == null) return false;
        return normalizar(a).equals(normalizar(b));
    }

    private String normalizar(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase().replaceAll("\\s+", " ");
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
