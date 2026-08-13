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
        /** Horario do apito inicial, ex. "21:00". */
        public String hora;

        /**
         * Identidade do jogo na fonte, lida do breadcrumb. Muito mais confiavel
         * que casar por nome: "Atlético-MG" e "Atletico Mineiro" sao o mesmo id.
         */
        public Long idExternoCasa;
        public Long idExternoFora;
        public String nacao;
        public String campeonato;
        public Long campeonatoIdExterno;

        public String arbitro;
        /** Media de cartoes AMARELOS por jogo deste arbitro, quando informada. */
        public Float arbitroMediaAmarelos;
        /** Media de cartoes VERMELHOS por jogo deste arbitro, quando informada. */
        public Float arbitroMediaVermelhos;

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
    /** Marcador do intervalo no fluxo da partida: um <span> com "HT 1 - 0". */
    private static final Pattern MARCA_HT = Pattern.compile("^HT\\s*(\\d{1,2})\\s*[-–:]\\s*(\\d{1,2})$");
    /** Marcador do fim de jogo: "FT 1 - 1". Usado so para conferencia. */
    private static final Pattern MARCA_FT = Pattern.compile("^FT\\s*(\\d{1,2})\\s*[-–:]\\s*(\\d{1,2})$");
    private static final Pattern ROTULO_ARBITRO = Pattern.compile("^(árbitro|arbitro|referee|juiz)\\s*:?$");
    /** /pt/football/team/botafogo/1958 */
    private static final Pattern HREF_TIME = Pattern.compile("/football/team/[^/]+/(\\d+)$");
    /** /pt/football/tournament/brazil/brasileirao-serie-a/325 */
    private static final Pattern HREF_TORNEIO = Pattern.compile("/football/tournament/([^/]+)/([^/]+)/(\\d+)$");
    /** /pt/football/brazil */
    private static final Pattern HREF_NACAO = Pattern.compile("^/[a-z]{2}/football/([a-z-]+)$");

    private static final Pattern HORA = Pattern.compile("^([01]?\\d|2[0-3]):([0-5]\\d)$");
    /** Classe atomica que o SofaScore usa para colorir o icone de cartao vermelho. */
    private static final String CLASSE_VERMELHO = "c_status.error.default";

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

        extrairPlacar1T(doc, r);
        extrairArbitro(doc, r);
        extrairHora(doc, r);
        extrairBreadcrumb(doc, r);

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
     * Placar do intervalo, lido do FLUXO DA PARTIDA.
     *
     * O SofaScore emite um <span> cujo texto e literalmente "HT 1 - 0" no meio
     * da linha do tempo de eventos, e outro com "FT 1 - 1" no fim. Nada de
     * busca por vizinhanca: a ancora e o proprio texto do no, no mesmo espirito
     * do <span> [digito, "-", digito] que ancora o placar final.
     *
     * O FT serve de conferencia. Se ele existir e divergir do placar do
     * cabecalho, algo esta errado na leitura e registramos um aviso em vez de
     * gravar dado suspeito em silencio.
     */
    private void extrairPlacar1T(Document doc, Resultado r) {
        Integer ftCasa = null, ftFora = null;

        for (Element sp : doc.select("span")) {
            if (!sp.children().isEmpty()) continue;
            String t = sp.text().trim();

            Matcher ht = MARCA_HT.matcher(t);
            if (ht.matches() && r.golsCasa1T == null) {
                r.golsCasa1T = Integer.parseInt(ht.group(1));
                r.golsFora1T = Integer.parseInt(ht.group(2));
                continue;
            }
            Matcher ft = MARCA_FT.matcher(t);
            if (ft.matches() && ftCasa == null) {
                ftCasa = Integer.parseInt(ft.group(1));
                ftFora = Integer.parseInt(ft.group(2));
            }
        }

        if (ftCasa != null && (ftCasa != r.golsCasa || ftFora != r.golsFora)) {
            r.avisos.add("Divergência: cabeçalho diz " + r.golsCasa + "-" + r.golsFora
                    + " mas o fluxo diz FT " + ftCasa + "-" + ftFora + ". Placar do 1º tempo descartado.");
            r.golsCasa1T = null;
            r.golsFora1T = null;
            return;
        }
        if (r.golsCasa1T == null) {
            r.avisos.add("Marcador HT não encontrado; esta partida não alimenta os mercados por tempo.");
            return;
        }
        if (r.golsCasa1T > r.golsCasa || r.golsFora1T > r.golsFora) {
            r.avisos.add("Placar do 1º tempo (" + r.golsCasa1T + "-" + r.golsFora1T
                    + ") maior que o final; descartado.");
            r.golsCasa1T = null;
            r.golsFora1T = null;
        }
    }

    /**
     * Arbitro e, quando disponivel, a media de cartoes dele.
     *
     * O bloco tem a forma: <span>Árbitro</span> <span>NOME</span>
     * <span>Média de cartões <svg/>0.24 <svg/>5.50</span>.
     *
     * A media de cartoes do arbitro e o preditor isolado mais forte do mercado
     * de cartoes -- vale mais que a media dos dois clubes somada. Capturar isto
     * e o maior ganho gratuito deste parser.
     *
     * Qual numero e vermelho e qual e amarelo: preferimos a classe atomica do
     * <svg> ("c_status.error.default" = vermelho). Se a classe mudar, caimos
     * numa regra de magnitude -- nenhum arbitro do mundo da mais vermelhos que
     * amarelos, entao o maior dos dois e sempre o amarelo.
     */
    private void extrairArbitro(Document doc, Resultado r) {
        for (Element sp : doc.select("span")) {
            if (!sp.children().isEmpty()) continue;
            if (!ROTULO_ARBITRO.matcher(normalizar(sp.ownText())).matches()) continue;

            Element bloco = sp.parent();
            if (bloco == null) continue;

            for (Element cand : bloco.select("span")) {
                String txt = cand.text().trim();
                if (txt.isEmpty() || cand == sp) continue;
                if (normalizar(txt).startsWith("media de cartoes")
                        || normalizar(txt).startsWith("média de cartões")) continue;
                if (txt.length() >= 3 && txt.length() <= 60 && !txt.matches(".*\\d.*")) {
                    r.arbitro = txt;
                    break;
                }
            }
            lerMediaCartoes(bloco, r);
            if (r.arbitro != null) return;
        }
    }

    private void lerMediaCartoes(Element bloco, Resultado r) {
        Float vermelho = null, amarelo = null;
        List<Float> todos = new ArrayList<>();

        for (Element svg : bloco.select("svg")) {
            String classe = svg.className();
            // o numero da media e o texto imediatamente apos o <svg>
            org.jsoup.nodes.Node prox = svg.nextSibling();
            String txt = prox != null ? prox.toString().trim() : "";
            Matcher mm = NUM.matcher(txt);
            if (!mm.find()) continue;
            float v;
            try { v = Float.parseFloat(mm.group()); } catch (NumberFormatException e) { continue; }
            todos.add(v);
            if (classe.contains(CLASSE_VERMELHO)) vermelho = v;
        }
        if (todos.size() == 2) {
            if (vermelho != null) {
                amarelo = todos.get(0).equals(vermelho) ? todos.get(1) : todos.get(0);
            } else {
                // fallback por magnitude: amarelo e sempre o maior
                amarelo  = Math.max(todos.get(0), todos.get(1));
                vermelho = Math.min(todos.get(0), todos.get(1));
                r.avisos.add("Cor do ícone de cartão não identificada; média do árbitro inferida por magnitude.");
            }
            r.arbitroMediaAmarelos = amarelo;
            r.arbitroMediaVermelhos = vermelho;
        }
    }

    /** Horario do apito inicial, do bloco "Data e hora". */
    private void extrairHora(Document doc, Resultado r) {
        for (Element sp : doc.select("span, div")) {
            if (!sp.children().isEmpty()) continue;
            if (HORA.matcher(sp.ownText().trim()).matches()) {
                r.hora = sp.ownText().trim();
                return;
            }
        }
    }

    /**
     * Nacao, campeonato e ids externos dos clubes, lidos do breadcrumb.
     *
     * Os hrefs do SofaScore sao a coisa mais estavel da pagina inteira: o texto
     * visivel muda com patrocinio ("Brasileirão Betano" era "Brasileirão Assaí"),
     * a diagramacao muda a cada release, mas /football/team/botafogo/1958 nao
     * muda. Guardar o id externo elimina a adivinhacao de nome na importacao --
     * casamos por identidade, e o nome vira so rotulo.
     *
     * O torneio aparece duas vezes no breadcrumb: uma com fragmento "#id:87"
     * (temporada) e o texto poluido pela rodada, outra limpa. O regex exige o
     * final em digitos, o que descarta a versao com fragmento.
     */
    private void extrairBreadcrumb(Document doc, Resultado r) {
        List<Element> links = doc.select("a[href]");

        for (Element a : links) {
            String href = a.attr("href");

            Matcher mt = HREF_TORNEIO.matcher(href);
            if (mt.find() && r.campeonato == null) {
                r.campeonato = a.text().trim();
                r.campeonatoIdExterno = Long.parseLong(mt.group(3));
                continue;
            }
            Matcher mn = HREF_NACAO.matcher(href);
            if (mn.matches() && r.nacao == null) {
                String t = a.text().trim();
                // o primeiro link e o esporte ("Futebol"); a nacao vem depois
                if (!t.isEmpty() && !normalizar(t).equals("futebol")) r.nacao = t;
            }
        }

        // Times: casamos pelo TEXTO do link com os nomes ja extraidos, em vez de
        // confiar na ordem. Se o layout inverter os links, o id vai junto do nome
        // certo em vez de trocar mandante por visitante em silencio.
        List<Element> times = new ArrayList<>();
        for (Element a : links) {
            if (HREF_TIME.matcher(a.attr("href")).find()) times.add(a);
        }
        for (Element a : times) {
            Matcher m = HREF_TIME.matcher(a.attr("href"));
            if (!m.find()) continue;
            long id = Long.parseLong(m.group(1));
            String txt = a.text().trim();
            if (igual(txt, r.nomeClubeCasa) && r.idExternoCasa == null) r.idExternoCasa = id;
            else if (igual(txt, r.nomeClubeFora) && r.idExternoFora == null) r.idExternoFora = id;
        }
        if ((r.idExternoCasa == null || r.idExternoFora == null) && times.size() >= 2) {
            r.avisos.add("IDs externos casados por posição, não por nome; confira o mandante.");
            if (r.idExternoCasa == null) {
                Matcher m = HREF_TIME.matcher(times.get(0).attr("href"));
                if (m.find()) r.idExternoCasa = Long.parseLong(m.group(1));
            }
            if (r.idExternoFora == null) {
                Matcher m = HREF_TIME.matcher(times.get(1).attr("href"));
                if (m.find()) r.idExternoFora = Long.parseLong(m.group(1));
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