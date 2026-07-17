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

    /** true se os dois nomes designam, muito provavelmente, o mesmo clube. */
    public static boolean equivalentes(String a, String b) {
        if (normalizar(a).equals(normalizar(b))) return true;
        Set<String> ta = tokens(a), tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return false;
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

        List<Clube> vencedores = new ArrayList<>();
        int melhorScore = 0;
        for (Clube c : candidatos) {
            Set<String> tc = tokens(c.getNome());
            if (tc.isEmpty()) continue;
            if (!(tc.containsAll(alvo) || alvo.containsAll(tc))) continue;
            int score = Math.min(alvo.size(), tc.size());
            if (score > melhorScore) { melhorScore = score; vencedores.clear(); vencedores.add(c); }
            else if (score == melhorScore) { vencedores.add(c); }
        }
        // ambiguidade -> nao decide
        return vencedores.size() == 1 ? vencedores.get(0) : null;
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
