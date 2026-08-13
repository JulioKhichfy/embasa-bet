package com.footballstats.parser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser da pagina de um jogo na bet365.
 *
 * AO CONTRARIO DO QUE EU SUPUS, AS CLASSES NAO SAO OFUSCADAS. A bet365 usa
 * nomes semanticos e estaveis (gl-MarketGroupPod, gl-Market_General,
 * bbl-BetBuilderParticipant_Odds), o que torna a extracao muito mais solida do
 * que uma busca por texto solto.
 *
 * ESTRUTURA (verificada contra um dump real de Fluminense x Palmeiras)
 * --------------------------------------------------------------------
 *   .gl-MarketGroupPod                      um mercado
 *     .gl-MarketGroupButton_Text            o titulo, ex. "Escanteios"
 *     .gl-Market_General                    UMA COLUNA (layout e coluna-a-coluna)
 *       .gl-Market_General-haslabels        coluna de rotulos de linha
 *         .bbl-BetBuilderParticipantLabel_Name          ex. "5 Escanteios"
 *         .bbl-BetBuilderParticipantLabelCentered_Name  variante centrada, ex. "7.5"
 *       .bbl-StickyMarketColumnHeader_Label cabecalho da coluna, ex. "Mais de"
 *       .bbl-BetBuilderParticipant          uma celula de odd
 *
 * POR QUE PARSEAR O DOM E NAO O TEXTO
 * -----------------------------------
 * No texto corrido, "Menos de" no mercado Total de Gols aparece com 6 valores
 * para 7 linhas, porque nao existe "menos de 0 gols". Nao da para saber qual
 * linha ficou de fora e todo o alinhamento quebra.
 *
 * No DOM, a bet365 emite uma celula VAZIA nessa posicao. O grid e perfeitamente
 * retangular e o alinhamento por indice e exato. Este e o argumento pratico
 * para a extensao despejar HTML e o parse acontecer aqui.
 *
 * DOIS CASOS QUE EXIGEM CUIDADO
 * -----------------------------
 * 1. UM POD PODE TER VARIOS GRIDS. "Margem de Vitória" traz dois lado a lado
 *    ("X ou Mais Gols" e "X Gols" exatos), com cabecalhos repetidos. Tratar as
 *    colunas como uma lista unica mistura os dois. Segmentamos: toda coluna de
 *    rotulos inicia um grid novo.
 *
 * 2. ABAS NAO RENDERIZADAS. Pods com .bbl-TabSwitcherItem_TabText (Partida /
 *    1º Tempo / 2º Tempo / Primeiros 10 Minutos) so tem no DOM a aba ativa. As
 *    demais nao existem -- nao vieram vazias, nao vieram. Registramos em
 *    {@link Resultado#avisos} para a extensao saber que precisa clicar.
 */
public class Bet365Parser {

    /** Uma cotacao: mercado + linha + selecao + odd. */
    public static class Cotacao {
        public String mercado;    // titulo do pod, ex. "Escanteios"
        public String linha;      // rotulo da linha, ex. "5 Escanteios" (pode ser null)
        public String selecao;    // cabecalho da coluna, ex. "Mais de" (pode ser null)
        public double odd;

        public Cotacao(String mercado, String linha, String selecao, double odd) {
            this.mercado = mercado; this.linha = linha; this.selecao = selecao; this.odd = odd;
        }
        @Override public String toString() {
            return mercado + " | " + linha + " | " + selecao + " = " + odd;
        }
    }

    public static class Resultado {
        public String clubeCasa;
        public String clubeFora;
        public final List<Cotacao> cotacoes = new ArrayList<>();
        /** Titulos de pods que existiam mas nao renderam grid nenhum. */
        public final Set<String> mercadosVazios = new LinkedHashSet<>();
        public final List<String> avisos = new ArrayList<>();
    }

    private static final Pattern ODD = Pattern.compile("^\\d+(?:\\.\\d+)?$");

    private static final String SEL_POD     = ".gl-MarketGroupPod";
    private static final String SEL_TITULO  = ".gl-MarketGroupButton_Text";
    private static final String SEL_COLUNA  = ".gl-Market_General";
    private static final String SEL_ROTULO  = ".bbl-BetBuilderParticipantLabel_Name, .bbl-BetBuilderParticipantLabelCentered_Name";
    private static final String SEL_CABEC   = ".bbl-StickyMarketColumnHeader_Label";
    private static final String SEL_CELULA  = ".bbl-BetBuilderParticipant";
    private static final String SEL_ABA     = ".bbl-TabSwitcherItem_TabText";
    private static final String CLS_LABELS  = "gl-Market_General-haslabels";

    public Resultado parse(String html) {
        Document doc = Jsoup.parse(html);
        Resultado r = new Resultado();

        extrairClubes(doc, r);

        for (Element pod : doc.select(SEL_POD)) {
            Element t = pod.selectFirst(SEL_TITULO);
            if (t == null) continue;
            String mercado = t.text().trim();
            if (mercado.isEmpty()) continue;

            int antes = r.cotacoes.size();
            for (Grid g : segmentarGrids(pod)) {
                emitir(mercado, g, r);
            }
            if (r.cotacoes.size() == antes) r.mercadosVazios.add(mercado);

            // Colunas sem cabeçalho e em quantidade > 1: as odds existem, mas não
            // se sabe a que seleção cada uma pertence (ex.: "Defesas de Goleiro",
            // cujos rótulos "1 ou Mais"/"2 ou Mais" ficam num scroller que não
            // renderizou). Melhor sinalizar que gravar odd sem identidade.
            long semSelecao = r.cotacoes.stream()
                    .filter(c -> c.mercado.equals(mercado) && c.selecao == null).count();
            if (semSelecao > 1) {
                r.avisos.add(mercado + ": " + semSelecao + " odd(s) sem rótulo de seleção"
                        + " — as linhas não renderizaram no dump e não dá para saber a qual"
                        + " seleção cada odd pertence.");
            }

            avisarAbas(pod, mercado, r);
        }
        return r;
    }

    // ------------------------------------------------------------------

    /** Um grid: uma coluna de rotulos + as colunas de odds que a seguem. */
    private static class Grid {
        final List<String> rotulos = new ArrayList<>();
        final List<String> cabecalhos = new ArrayList<>();
        final List<List<String>> colunas = new ArrayList<>();
    }

    /**
     * Quebra o pod em grids. Regra: toda coluna marcada com -haslabels comeca um
     * grid novo. Um pod sem coluna de rotulos (ex. "Tempo Com Mais Gols") vira um
     * unico grid de rotulos vazios, e as celulas se identificam pelo cabecalho.
     */
    private List<Grid> segmentarGrids(Element pod) {
        List<Grid> grids = new ArrayList<>();
        Grid atual = null;

        for (Element col : pod.select(SEL_COLUNA)) {
            boolean ehRotulos = col.hasClass(CLS_LABELS);

            if (ehRotulos || atual == null) {
                atual = new Grid();
                grids.add(atual);
            }
            if (ehRotulos) {
                for (Element l : col.select(SEL_ROTULO)) atual.rotulos.add(l.text().trim());
                continue;
            }

            Element h = col.selectFirst(SEL_CABEC);
            atual.cabecalhos.add(h != null ? h.text().trim() : "");

            List<String> celulas = new ArrayList<>();
            for (Element c : col.select(SEL_CELULA)) celulas.add(c.text().trim());
            atual.colunas.add(celulas);
        }
        return grids;
    }

    /**
     * Converte um grid em cotacoes.
     *
     * Celula vazia = a casa nao ofertou aquela combinacao (ex. "menos de 0
     * gols"). Ela ocupa lugar no grid para manter o alinhamento, mas nao vira
     * cotacao.
     */
    private void emitir(String mercado, Grid g, Resultado r) {
        for (int c = 0; c < g.colunas.size(); c++) {
            List<String> celulas = g.colunas.get(c);
            String cabecalho = c < g.cabecalhos.size() ? g.cabecalhos.get(c) : "";

            if (!g.rotulos.isEmpty() && celulas.size() != g.rotulos.size()) {
                r.avisos.add(mercado + ": coluna '" + cabecalho + "' tem " + celulas.size()
                        + " célula(s) para " + g.rotulos.size() + " linha(s); coluna ignorada"
                        + " para não desalinhar as odds.");
                continue;
            }

            for (int i = 0; i < celulas.size(); i++) {
                String txt = celulas.get(i);
                if (txt.isEmpty()) continue;
                if (!ODD.matcher(txt).matches()) continue;

                String rotulo = i < g.rotulos.size() ? g.rotulos.get(i) : null;
                r.cotacoes.add(new Cotacao(mercado, rotulo,
                        cabecalho.isEmpty() ? null : cabecalho, Double.parseDouble(txt)));
            }
        }
    }

    /**
     * Um pod com abas so tem no DOM a aba ativa. Avisa quais existem, para a
     * extensao clicar e salvar um dump por aba.
     */
    private void avisarAbas(Element pod, String mercado, Resultado r) {
        List<String> abas = new ArrayList<>();
        for (Element a : pod.select(SEL_ABA)) {
            String t = a.text().trim();
            if (!t.isEmpty()) abas.add(t);
        }
        if (abas.size() > 1) {
            r.avisos.add(mercado + ": tem abas " + abas
                    + " e só a ativa está no HTML; capture um dump por aba.");
        }
    }

    /**
     * Nomes dos clubes.
     *
     * No pod "Resultado" as LINHAS sao os periodos (Partida, 1º Tempo, 2º Tempo,
     * 10 Minutos) e as COLUNAS sao Fluminense / Empate / Palmeiras. Ou seja, os
     * clubes estao nos cabecalhos de coluna -- o inverso do que a leitura do
     * texto corrido sugere. Deduzir do <title> seria pior: a bet365 muda o
     * formato dele.
     */
    private void extrairClubes(Document doc, Resultado r) {
        for (Element pod : doc.select(SEL_POD)) {
            Element t = pod.selectFirst(SEL_TITULO);
            if (t == null || !t.text().trim().equalsIgnoreCase("Resultado")) continue;

            List<String> nomes = new ArrayList<>();
            for (Element h : pod.select(SEL_CABEC)) {
                String n = h.text().trim();
                if (n.isEmpty() || n.equalsIgnoreCase("Empate")) continue;
                if (!nomes.contains(n)) nomes.add(n);
            }
            if (nomes.size() >= 2) {
                r.clubeCasa = nomes.get(0);
                r.clubeFora = nomes.get(nomes.size() - 1);
            } else {
                r.avisos.add("Pod 'Resultado' sem cabeçalhos de clube utilizáveis.");
            }
            return;
        }
        r.avisos.add("Pod 'Resultado' não encontrado; nomes dos clubes não extraídos.");
    }
}