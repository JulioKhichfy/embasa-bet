package com.footballstats.service;

import com.footballstats.model.Clube;

import java.text.Normalizer;
import java.util.*;

/**
 * Normalizacao e equivalencia de nomes de clubes.
 *
 * Motivacao: o SofaScore usa nomes variados para o mesmo clube
 * ("Vasco" / "Vasco da Gama", "Athletico-MG" / "Atletico Mineiro",
 *  "RB Bragantino" / "Red Bull Bragantino"), o que criava clubes duplicados
 * na importacao.
 *
 * Estrategia (validada contra os casos reais):
 *  1) normalizar: minusculas, sem acento, sem pontuacao;
 *  2) expandir sufixo de UF para o adjetivo correspondente
 *     ("athletico-mg" -> [atletico, mineiro]), o que PRESERVA a distincao
 *     entre Athletico-MG e Athletico-PR;
 *  3) unificar grafias ("athletico" -> "atletico");
 *  4) remover palavras que nao distinguem ("fc", "red bull"/"rb", "da", ...);
 *  5) considerar equivalentes quando um conjunto de tokens contem o outro.
 *
 * IMPORTANTE: quando mais de um clube empata como candidato (ex.: "Athletico"
 * com "Athletico-MG" e "Athletico-PR" cadastrados), a equivalencia e RECUSADA.
 * Adivinhar nesse caso corromperia os dados; o usuario resolve manualmente
 * pela tela de fusao de clubes.
 */
public final class ClubeNomes {

    private ClubeNomes() {}

    /** Palavras que nao ajudam a distinguir clubes. */
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "fc", "ec", "sc", "cf", "ac", "af", "sad", "clube", "club", "futebol",
            "esporte", "esportivo", "esportiva", "sociedade", "associacao",
            "de", "do", "da", "das", "dos", "e", "the",
            "red", "bull", "rb"   // RB Bragantino == Red Bull Bragantino
    ));

    /** Sufixo de UF -> adjetivo. Mantem Athletico-MG != Athletico-PR. */
    private static final Map<String, String> UF_ADJETIVO = new HashMap<>();
    static {
        UF_ADJETIVO.put("mg", "mineiro");
        UF_ADJETIVO.put("pr", "paranaense");
        UF_ADJETIVO.put("go", "goianiense");
        UF_ADJETIVO.put("rj", "rio");
        UF_ADJETIVO.put("sp", "paulista");
        UF_ADJETIVO.put("rs", "gaucho");
        UF_ADJETIVO.put("ba", "baiano");
        UF_ADJETIVO.put("ce", "cearense");
        UF_ADJETIVO.put("pe", "pernambucano");
        UF_ADJETIVO.put("sc", "catarinense");
        UF_ADJETIVO.put("mt", "matogrossense");
        UF_ADJETIVO.put("ms", "sulmatogrossense");
        UF_ADJETIVO.put("pa", "paraense");
        UF_ADJETIVO.put("pb", "paraibano");
        UF_ADJETIVO.put("rn", "potiguar");
        UF_ADJETIVO.put("al", "alagoano");
        UF_ADJETIVO.put("se", "sergipano");
        UF_ADJETIVO.put("pi", "piauiense");
        UF_ADJETIVO.put("ma", "maranhense");
        UF_ADJETIVO.put("am", "amazonense");
        UF_ADJETIVO.put("ap", "amapaense");
        UF_ADJETIVO.put("ac", "acreano");
        UF_ADJETIVO.put("ro", "rondoniense");
        UF_ADJETIVO.put("rr", "roraimense");
        UF_ADJETIVO.put("to", "tocantinense");
        UF_ADJETIVO.put("df", "brasiliense");
        UF_ADJETIVO.put("es", "capixaba");
    }

    /** Grafias alternativas do mesmo termo. */
    private static final Map<String, String> ALIAS = new HashMap<>();
    static {
        ALIAS.put("athletico", "atletico");
        ALIAS.put("atlhetico", "atletico");
    }

    /**
     * Tokens "distintivos": adjetivos regionais/UF que, quando presentes SO de
     * um lado, indicam clubes DIFERENTES (Atletico Mineiro x Atletico Paranaense).
     *
     * Sao os proprios valores expandidos de UF_ADJETIVO (mineiro, paranaense,
     * goianiense, ...). Assim, "Athletico" (sem UF) NAO e tratado como igual a
     * "Atletico-MG" (com "mineiro"): a diferenca esta num token distintivo, entao
     * a equivalencia e recusada e a sugestao de fusao nao aparece.
     */
    private static final Set<String> REGIONAIS = new HashSet<>(UF_ADJETIVO.values());

    /** Minusculas, sem acento, sem pontuacao, espacos colapsados. */
    public static String normalizar(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return n.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    /** Tokens significativos do nome, com UF expandida e aliases aplicados. */
    public static Set<String> tokens(String nome) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : normalizar(nome).split(" ")) {
            if (t.isEmpty()) continue;
            if (t.length() == 2 && UF_ADJETIVO.containsKey(t)) { out.add(UF_ADJETIVO.get(t)); continue; }
            if (STOPWORDS.contains(t)) continue;
            out.add(ALIAS.getOrDefault(t, t));
        }
        return out;
    }

    /** Subconjunto de tokens que sao adjetivos regionais/UF (distintivos). */
    private static Set<String> regionais(Set<String> tokens) {
        Set<String> r = new HashSet<>();
        for (String t : tokens) if (REGIONAIS.contains(t)) r.add(t);
        return r;
    }

    /**
     * true se os dois nomes designam, muito provavelmente, o mesmo clube.
     *
     * Regras (acento e caixa ja sao ignorados pela normalizacao):
     *  1) se a forma normalizada e identica -> equivalentes;
     *  2) senao, exige-se que um conjunto de tokens contenha o outro
     *     (ex.: "Vasco" ⊆ "Vasco da Gama");
     *  3) MAS os adjetivos REGIONAIS presentes precisam ser COMPATIVEIS:
     *     - se os dois lados tem regional, precisam ser exatamente os mesmos
     *       (Atletico Mineiro != Atletico Paranaense);
     *     - se so um lado tem regional (o outro e "cru", tipo "Athletico"),
     *       a diferenca esta num token distintivo -> NAO sao equivalentes,
     *       pois o nome cru e ambiguo (poderia ser qualquer UF).
     */
    public static boolean equivalentes(String a, String b) {
        if (normalizar(a).equals(normalizar(b))) return true;
        Set<String> ta = tokens(a), tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return false;

        Set<String> ra = regionais(ta), rb = regionais(tb);
        // Regionais devem coincidir exatamente. Cobre os dois casos problematicos:
        //  - regionais diferentes (mineiro x paranaense) -> false
        //  - um lado sem regional e o outro com -> false (nome cru e ambiguo)
        if (!ra.equals(rb)) return false;

        return ta.containsAll(tb) || tb.containsAll(ta);
    }

    /**
     * Melhor clube equivalente dentro de 'candidatos'.
     * Retorna null se nao houver nenhum OU se houver EMPATE entre dois ou mais
     * (ambiguidade: melhor criar/registrar separado do que unir errado).
     */
    public static Clube melhorEquivalente(String nome, Collection<Clube> candidatos) {
        String alvoNorm = normalizar(nome);
        if (alvoNorm.isEmpty()) return null;

        for (Clube c : candidatos) {
            if (normalizar(c.getNome()).equals(alvoNorm)) return c;
        }

        Set<String> alvo = tokens(nome);
        if (alvo.isEmpty()) return null;
        Set<String> alvoReg = regionais(alvo);

        List<Clube> vencedores = new ArrayList<>();
        int melhorScore = 0;
        for (Clube c : candidatos) {
            Set<String> tc = tokens(c.getNome());
            if (tc.isEmpty()) continue;
            // regionais precisam coincidir (mesma regra de equivalentes)
            if (!regionais(tc).equals(alvoReg)) continue;
            if (!(tc.containsAll(alvo) || alvo.containsAll(tc))) continue;
            int score = Math.min(alvo.size(), tc.size());
            if (score > melhorScore) { melhorScore = score; vencedores.clear(); vencedores.add(c); }
            else if (score == melhorScore) { vencedores.add(c); }
        }
        // ambiguidade -> nao decide
        return vencedores.size() == 1 ? vencedores.get(0) : null;
    }

    /**
     * true se 'nome' casa algum apelido do conjunto, comparando de forma
     * NORMALIZADA (ignora acento, caixa e pontuacao). E o criterio escolhido
     * para apelidos manuais: preciso o suficiente para nao confundir clubes,
     * mas tolerante a "Atletico-MG" == "atletico mg" == "ATLÉTICO MG".
     */
    public static boolean casaApelido(String nome, Collection<String> apelidos) {
        if (nome == null || apelidos == null || apelidos.isEmpty()) return false;
        String alvo = normalizar(nome);
        if (alvo.isEmpty()) return false;
        for (String ap : apelidos) {
            if (normalizar(ap).equals(alvo)) return true;
        }
        return false;
    }

    /** Pares de clubes suspeitos de serem duplicados (sugestao para a UI). */
    public static List<Clube[]> sugerirDuplicados(List<Clube> clubes) {
        List<Clube[]> pares = new ArrayList<>();
        for (int i = 0; i < clubes.size(); i++) {
            for (int j = i + 1; j < clubes.size(); j++) {
                if (equivalentes(clubes.get(i).getNome(), clubes.get(j).getNome())) {
                    pares.add(new Clube[]{ clubes.get(i), clubes.get(j) });
                }
            }
        }
        return pares;
    }
}