package com.footballstats.service;

import com.footballstats.model.Odd;
import com.footballstats.model.Projecao;
import com.footballstats.repository.OddRepository;
import com.footballstats.repository.ProjecaoRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Cruza PROJECAO com ODD e calcula onde ha valor.
 *
 * A MARGEM DA CASA E O PASSO QUE QUASE TODO MUNDO PULA
 * -----------------------------------------------------
 * 1/odd NAO e a probabilidade que a casa atribui ao evento -- e a probabilidade
 * mais a margem dela. Num mercado Sim/Não com odds 1.95 e 1.80, as implicitas
 * somam 1.07: sete pontos percentuais que sao lucro da casa, nao crenca.
 *
 * Comparar a nossa probabilidade contra a implicita CRUA faz toda aposta
 * parecer ruim, porque estamos competindo contra um numero inflado. Descontamos
 * a margem antes: e so contra a probabilidade JUSTA que a nossa estimativa
 * significa alguma coisa.
 *
 * Metodo: proporcional -- divide cada implicita pela soma do mercado. Simples e
 * suficiente para comecar. LIMITACAO CONHECIDA: a margem real nao se distribui
 * igual entre as selecoes; as casas carregam mais no azarao (favourite-longshot
 * bias), entao o metodo proporcional SUPERESTIMA a probabilidade justa dos
 * azaroes e faz aposta em odd alta parecer melhor do que e. Se voce for apostar
 * sistematicamente em odds acima de ~4, troque por Shin ou power.
 *
 * SO DA PARA DESCONTAR A MARGEM COM O MERCADO COMPLETO. Se so capturamos um
 * lado, nao ha como separar margem de crenca -- nesses casos marcamos a
 * cotacao como sem desconto e o EV sai pessimista de proposito.
 */
@Service
public class ValorService {

    private final ProjecaoRepository projecaoRepo;
    private final OddRepository oddRepo;

    public ValorService(ProjecaoRepository projecaoRepo, OddRepository oddRepo) {
        this.projecaoRepo = projecaoRepo;
        this.oddRepo = oddRepo;
    }

    /** Uma oportunidade avaliada. */
    public static class Aposta {
        public String mercado;
        public Double linha;
        public Double linhaAte;
        public String selecao;
        public double odd;
        /** nossa probabilidade */
        public double pModelo;
        /** 1/odd, com a margem da casa embutida */
        public double pImplicitaBruta;
        /** implícita depois de descontar a margem do mercado */
        public double pJusta;
        /** soma das implícitas do mercado; 1.07 = 7% de margem */
        public double overround;
        /** false quando não tínhamos o mercado completo para descontar */
        public boolean margemDescontada;
        /** retorno esperado por unidade apostada */
        public double ev;
        /** fração da banca por Kelly integral (use uma fração dela) */
        public double kelly;
        public int amostra;

        public String descricao() {
            String l = linha == null ? "" : (linhaAte != null ? " " + fmt(linha) + "-" + fmt(linhaAte)
                    : " " + fmt(linha));
            return mercado + l + " " + selecao;
        }
        private static String fmt(double d) {
            return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
    }

    /**
     * Avalia todas as combinações projeção x odd de um confronto.
     *
     * @param evMinimo   piso de EV para entrar na lista (0.05 = 5%)
     * @param fracaoKelly fração de Kelly aplicada ao stake sugerido (0.25 é o usual)
     */
    public List<Aposta> avaliar(Long confrontoId, double evMinimo, double fracaoKelly) {
        List<Projecao> projecoes = projecaoRepo.findByConfrontoId(confrontoId);
        List<Odd> odds = oddRepo.findAtuaisByConfronto(confrontoId);
        if (projecoes.isEmpty() || odds.isEmpty()) return List.of();

        Map<String, Double> overrounds = calcularOverrounds(odds);

        List<Aposta> saida = new ArrayList<>();
        for (Odd o : odds) {
            Projecao p = casar(projecoes, o);
            if (p == null) continue;
            if (o.getValor() == null || o.getValor() <= 1) continue;

            String chave = chaveMercado(o);
            Double over = overrounds.get(chave);

            Aposta a = new Aposta();
            a.mercado = o.getMercado();
            a.linha = o.getLinha();
            a.linhaAte = o.getLinhaAte();
            a.selecao = o.getSelecao();
            a.odd = o.getValor();
            a.pModelo = p.getProbabilidade();
            a.amostra = p.getAmostra() == null ? 0 : p.getAmostra();
            a.pImplicitaBruta = 1.0 / o.getValor();

            if (over != null && over > 1.0001) {
                a.pJusta = a.pImplicitaBruta / over;
                a.overround = over;
                a.margemDescontada = true;
            } else {
                a.pJusta = a.pImplicitaBruta;
                a.overround = over == null ? 1.0 : over;
                a.margemDescontada = false;
            }

            a.ev = a.pModelo * (a.odd - 1) - (1 - a.pModelo);
            a.kelly = kelly(a.pModelo, a.odd) * fracaoKelly;

            if (a.ev >= evMinimo) saida.add(a);
        }
        saida.sort(Comparator.comparingDouble((Aposta x) -> x.ev).reversed());
        return saida;
    }

    /**
     * Fracao de Kelly: f = (p*odd - 1) / (odd - 1).
     *
     * Negativa significa aposta perdedora -- devolvemos 0. Kelly integral e
     * agressivo demais na pratica porque assume que p esta certo; como a nossa
     * p vem de um modelo com erro, use uma fracao (um quarto e o usual).
     */
    public static double kelly(double p, double odd) {
        if (odd <= 1) return 0;
        double f = (p * odd - 1) / (odd - 1);
        return Math.max(0, f);
    }

    /**
     * Overround por mercado: soma das implicitas de todas as selecoes daquele
     * (mercado, linha). Acima de 1 e a margem da casa.
     */
    private Map<String, Double> calcularOverrounds(List<Odd> odds) {
        Map<String, Double> soma = new LinkedHashMap<>();
        Map<String, Integer> qtd = new LinkedHashMap<>();
        for (Odd o : odds) {
            if (o.getValor() == null || o.getValor() <= 1) continue;
            String k = chaveMercado(o);
            soma.merge(k, 1.0 / o.getValor(), Double::sum);
            qtd.merge(k, 1, Integer::sum);
        }
        // Mercado com uma selecao so nao permite separar margem de crenca.
        Map<String, Double> out = new LinkedHashMap<>();
        soma.forEach((k, v) -> { if (qtd.get(k) >= 2) out.put(k, v); });
        return out;
    }

    private String chaveMercado(Odd o) {
        return o.getMercado() + "|" + o.getLinha() + "|" + o.getLinhaAte();
    }

    private Projecao casar(List<Projecao> projecoes, Odd o) {
        for (Projecao p : projecoes) {
            if (!p.getMercado().equals(o.getMercado())) continue;
            if (!Objects.equals(p.getSelecao(), o.getSelecao())) continue;
            if (!iguais(p.getLinha(), o.getLinha())) continue;
            if (!iguais(p.getLinhaAte(), o.getLinhaAte())) continue;
            return p;
        }
        return null;
    }

    private boolean iguais(Double a, Double b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return Math.abs(a - b) < 1e-9;
    }
}