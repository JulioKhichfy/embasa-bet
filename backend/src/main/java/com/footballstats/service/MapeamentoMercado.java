package com.footballstats.service;

import com.footballstats.model.Mercados;

import java.text.Normalizer;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Traduz o vocabulario da bet365 para a taxonomia de {@link Mercados}.
 *
 * O parser devolve tripla crua (titulo do pod, rotulo da linha, cabecalho da
 * coluna). Ex.: ("Escanteios", "5 Escanteios", "Mais de"). Aqui isso vira
 * ("escanteios", linha=5.0, MAIS).
 *
 * DUAS ARMADILHAS SEMANTICAS QUE ESTE MAPEAMENTO RESOLVE
 * ------------------------------------------------------
 * 1. "N ou Mais Gols" NAO e a mesma coisa que "Mais de N".
 *    Margem de Vitoria oferece "2 ou Mais Gols" = P(margem >= 2). Nossa
 *    taxonomia usa OVER como piso EXCLUSIVO: P(X > linha). Entao "2 ou Mais"
 *    vira linha = 1, nao 2. Copiar o numero do rotulo produz um erro de uma
 *    casa inteira -- e o pior tipo de erro, porque o resultado continua
 *    plausivel e ninguem percebe.
 *
 * 2. O EIXO DO PERIODO MUDA DE LUGAR conforme o mercado.
 *    Em "Para Ambos os Times Marcarem" o periodo esta nas LINHAS (Partida /
 *    1º Tempo / 2º Tempo) e a selecao nas COLUNAS (Sim / Não). Em "Margem de
 *    Vitória" o periodo esta em ABAS. Em "Escanteios" nao existe periodo. Por
 *    isso o mapeamento le as duas coordenadas juntas, nunca uma isolada.
 *
 * PRINCIPIO: quando nao souber, NAO CHUTE. Devolve Optional.empty() com o
 * motivo, exatamente como o resolverClube se recusa a fundir clube ambiguo.
 * Odd mapeada errado e pior que odd nao mapeada -- a segunda voce ve faltando,
 * a primeira entra no motor e envenena o EV em silencio.
 */
public class MapeamentoMercado {

    public enum Selecao {
        SIM, NAO,
        MAIS, MENOS, EXATAMENTE,
        CASA, FORA, EMPATE,
        PRIMEIRO_TEMPO, SEGUNDO_TEMPO
    }

    /**
     * @param mercado  codigo em {@link Mercados}
     * @param linha    limiar; para OVER e sempre piso EXCLUSIVO: P(X > linha)
     * @param linhaAte limite superior inclusivo em mercados de faixa; senao null
     */
    public record Traduzido(String mercado, Double linha, Double linhaAte, Selecao selecao) { }

    /** Falha de traducao, com o motivo legivel para o relatorio de importacao. */
    public record Falha(String motivo) { }

    // "5 Escanteios", "3 Cartões", "2 Gols"
    private static final Pattern N_SUBSTANTIVO = Pattern.compile("^(\\d+)\\s+\\p{L}+$");
    // "1-2 Gols", "3-6 Gols"
    private static final Pattern FAIXA         = Pattern.compile("^(\\d+)\\s*-\\s*(\\d+)\\s+\\p{L}+$");
    // "2 ou Mais Gols", "1 ou Mais"
    private static final Pattern N_OU_MAIS     = Pattern.compile("^(\\d+)\\s+ou\\s+mais(\\s+\\p{L}+)?$");
    // "7.5", "10.5"
    private static final Pattern DECIMAL       = Pattern.compile("^(\\d+(?:[.,]\\d+)?)$");

    private final String clubeCasa;
    private final String clubeFora;

    public MapeamentoMercado(String clubeCasa, String clubeFora) {
        this.clubeCasa = norm(clubeCasa);
        this.clubeFora = norm(clubeFora);
    }

    /**
     * @param titulo  titulo do pod, ex. "Escanteios"
     * @param linha   rotulo da linha, ex. "5 Escanteios" (pode ser null)
     * @param selecao cabecalho da coluna, ex. "Mais de" (pode ser null)
     */
    public Optional<Traduzido> traduzir(String titulo, String linha, String selecao) {
        return switch (norm(titulo)) {
            case "para ambos os times marcarem" -> ambosMarcam(linha, selecao);
            case "total de gols"                -> contagem("total_gols", linha, selecao);
            case "escanteios"                   -> contagem("escanteios", linha, selecao);
            case "cartoes"                      -> contagem("cartoes", linha, selecao);
            case "faixa de gols"                -> faixaGols(linha, selecao);
            case "margem de vitoria"            -> margem(linha, selecao);
            case "tempo com mais gols"          -> tempoMaisGols(linha);
            case "total de chutes"              -> overDecimal("total_chutes", linha, selecao);
            case "total de chutes ao gol"       -> overDecimal("total_chutes_gol", linha, selecao);
            case "para ambos os times receberem cartoes" -> ambosCartao(linha, selecao);
            case "time - para conseguir o maior numero de" -> maiorNumeroDe(linha, selecao);
            default -> Optional.empty();
        };
    }

    /** Motivo da recusa, para o relatorio. Chame quando traduzir() vier vazio. */
    public Falha explicar(String titulo, String linha, String selecao) {
        return switch (norm(titulo)) {
            case "resultado", "chance dupla", "placar", "intervalo/final do jogo",
                    "partida/tempo - cartao vermelho", "defesas de goleiro" ->
                    new Falha("mercado '" + titulo + "' ainda não modelado em Mercados.java");
            default -> {
                if (titulo != null && norm(titulo).startsWith("jogador"))
                    yield new Falha("mercado de jogador individual — fora do escopo do motor");
                yield new Falha("não reconhecido: '" + titulo + "' / '" + linha + "' / '" + selecao + "'");
            }
        };
    }

    // ------------------------------------------------------------------
    // Regras por familia
    // ------------------------------------------------------------------

    /** Linhas = periodo, colunas = Sim/Não. */
    private Optional<Traduzido> ambosMarcam(String linha, String selecao) {
        Selecao s = simNao(selecao);
        String cod = switch (norm(linha)) {
            case "partida"   -> "btts";
            case "1o tempo"  -> "btts_1t";
            case "2o tempo"  -> "btts_2t";
            default -> null;
        };
        return (cod != null && s != null) ? ok(cod, null, null, s) : Optional.empty();
    }

    /** Só a coluna "Partida" existe na taxonomia hoje; 1º/2º tempo ficam de fora. */
    private Optional<Traduzido> ambosCartao(String linha, String selecao) {
        Selecao s = simNao(selecao);
        if (s == null || !norm(linha).equals("partida")) return Optional.empty();
        return ok("ambos_cartao", null, null, s);
    }

    /** "N Gols"/"N Escanteios"/"N Cartões" + Mais de / Menos de / Exatamente. */
    private Optional<Traduzido> contagem(String cod, String linha, String selecao) {
        Matcher m = N_SUBSTANTIVO.matcher(norm(linha));
        if (!m.matches()) return Optional.empty();
        Selecao s = maisMenos(selecao);
        if (s == null) return Optional.empty();
        return ok(cod, Double.parseDouble(m.group(1)), null, s);
    }

    /** Rótulos "7.5" na coluna de labels + Mais de / Menos de. */
    private Optional<Traduzido> overDecimal(String cod, String linha, String selecao) {
        Matcher m = DECIMAL.matcher(norm(linha).replace(',', '.'));
        if (!m.matches()) return Optional.empty();
        Selecao s = maisMenos(selecao);
        if (s == null) return Optional.empty();
        return ok(cod, Double.parseDouble(m.group(1)), null, s);
    }

    /** "1-2 Gols" -> linha=1, linhaAte=2. A bet365 oferece faixas com piso > 1. */
    private Optional<Traduzido> faixaGols(String linha, String selecao) {
        Matcher m = FAIXA.matcher(norm(linha));
        if (!m.matches()) return Optional.empty();
        Selecao s = simNao(selecao);
        if (s == null) return Optional.empty();
        double de  = Double.parseDouble(m.group(1));
        double ate = Double.parseDouble(m.group(2));
        if (de > ate) return Optional.empty();
        return ok("faixa_gols", de, ate, s);
    }

    /**
     * "2 ou Mais Gols" = P(margem >= 2) = P(margem > 1). Convertemos para o piso
     * exclusivo da taxonomia subtraindo 1. Ver armadilha 1 no cabeçalho.
     */
    private Optional<Traduzido> margem(String linha, String selecao) {
        Matcher m = N_OU_MAIS.matcher(norm(linha));
        if (!m.matches()) return Optional.empty();
        double n = Double.parseDouble(m.group(1));
        Selecao s = clubeOuEmpate(selecao);
        if (s == null) return Optional.empty();
        return ok("margem_vitoria", n - 1, null, s);
    }

    /** Três vias nas linhas, coluna única sem cabeçalho. */
    private Optional<Traduzido> tempoMaisGols(String linha) {
        String l = norm(linha);
        Selecao s;
        if (l.equals("1o tempo")) s = Selecao.PRIMEIRO_TEMPO;
        else if (l.equals("2o tempo")) s = Selecao.SEGUNDO_TEMPO;
        else if (l.contains("empate") || l.contains("nenhuma")) s = Selecao.EMPATE;
        else return Optional.empty();
        return ok("tempo_mais_gols", null, null, s);
    }

    /** Um pod, quatro mercados: a linha diz qual estatística. */
    private Optional<Traduzido> maiorNumeroDe(String linha, String selecao) {
        String cod = switch (norm(linha)) {
            case "cartoes"       -> "mais_cartoes";
            case "escanteios"    -> "mais_escanteios";
            case "chutes ao gol" -> "mais_chutes_gol";
            case "chutes"        -> "mais_chutes";
            default -> null;
        };
        Selecao s = clubeOuEmpate(selecao);
        return (cod != null && s != null) ? ok(cod, null, null, s) : Optional.empty();
    }

    // ------------------------------------------------------------------

    private Selecao simNao(String t) {
        return switch (norm(t)) {
            case "sim" -> Selecao.SIM;
            case "nao" -> Selecao.NAO;
            default -> null;
        };
    }

    private Selecao maisMenos(String t) {
        return switch (norm(t)) {
            case "mais de"    -> Selecao.MAIS;
            case "menos de"   -> Selecao.MENOS;
            case "exatamente" -> Selecao.EXATAMENTE;
            default -> null;
        };
    }

    /**
     * Coluna que traz nome de clube. Comparamos com os nomes que o proprio dump
     * declarou, em vez de posicao: a ordem das colunas nao e garantida, e
     * "Qualquer um dos Times" aparece no meio em Margem de Vitoria.
     */
    private Selecao clubeOuEmpate(String t) {
        String n = norm(t);
        if (n.isEmpty()) return null;
        if (n.equals("empate")) return Selecao.EMPATE;
        if (n.equals(clubeCasa)) return Selecao.CASA;
        if (n.equals(clubeFora)) return Selecao.FORA;
        return null; // "Qualquer um dos Times" e afins: não modelado, não chuta
    }

    private Optional<Traduzido> ok(String mercado, Double linha, Double ate, Selecao s) {
        // trava de sanidade: o código precisa existir na taxonomia
        if (Mercados.porCodigo(mercado).isEmpty()) return Optional.empty();
        return Optional.of(new Traduzido(mercado, linha, ate, s));
    }

    /** minúsculas, sem acento, espaços colapsados. "1º Tempo" -> "1o tempo". */
    static String norm(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s.trim().toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return t.replace('º', 'o').replace('ª', 'a').replaceAll("\\s+", " ");
    }
}