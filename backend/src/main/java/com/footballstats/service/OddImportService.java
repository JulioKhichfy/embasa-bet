package com.footballstats.service;

import com.footballstats.model.Clube;
import com.footballstats.model.Confronto;
import com.footballstats.model.Odd;
import com.footballstats.parser.Bet365Parser;
import com.footballstats.repository.ClubeRepository;
import com.footballstats.repository.ConfrontoRepository;
import com.footballstats.repository.OddRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Importa um dump HTML da bet365, traduz as cotacoes e persiste.
 *
 * O servico NAO cria Confronto. Confronto nasce do lado do SofaScore, que tem
 * nomes de clube consistentes com a nossa base, arbitro e horario. A bet365 so
 * traz preco. Se este import pudesse criar confrontos, duas fontes com grafias
 * diferentes passariam a disputar quem define a identidade do jogo -- e o
 * resultado seria clube duplicado, o problema que o resolverClube existe para
 * evitar.
 *
 * Quando o confronto nao e encontrado, a importacao FALHA com um relatorio
 * dizendo quais nomes vieram no dump. Falhar alto e melhor que gravar odds
 * penduradas em nada.
 */
@Service
public class OddImportService {

    private final ConfrontoRepository confrontoRepo;
    private final OddRepository oddRepo;
    private final ClubeRepository clubeRepo;

    public OddImportService(ConfrontoRepository confrontoRepo, OddRepository oddRepo,
                            ClubeRepository clubeRepo) {
        this.confrontoRepo = confrontoRepo;
        this.oddRepo = oddRepo;
        this.clubeRepo = clubeRepo;
    }

    /** Relatorio de uma importacao. */
    public static class Relatorio {
        public boolean sucesso;
        public String mensagem;
        public Long confrontoId;
        public String clubeCasa;
        public String clubeFora;
        public int cotacoesLidas;
        public int oddsGravadas;
        public int naoMapeadas;
        /** motivo -> quantas vezes ocorreu; enxuga relatorio de 200 linhas em 5. */
        public Map<String, Integer> motivos = new LinkedHashMap<>();
        public List<String> avisosParser = new ArrayList<>();

        static Relatorio falha(String msg) {
            Relatorio r = new Relatorio();
            r.sucesso = false;
            r.mensagem = msg;
            return r;
        }
    }

    /**
     * @param html        conteudo do dump da bet365
     * @param confrontoId id explicito; quando null, tentamos localizar pelos
     *                    nomes de clube que o proprio dump declarou
     */
    @Transactional
    public Relatorio importar(String html, Long confrontoId) {
        Bet365Parser.Resultado res = new Bet365Parser().parse(html);

        Confronto confronto;
        if (confrontoId != null) {
            confronto = confrontoRepo.findById(confrontoId).orElse(null);
            if (confronto == null) return Relatorio.falha("Confronto " + confrontoId + " não existe.");
        } else {
            Optional<Confronto> achado = localizar(res.clubeCasa, res.clubeFora);
            if (achado.isEmpty()) {
                return Relatorio.falha("Não encontrei confronto para '" + res.clubeCasa
                        + "' x '" + res.clubeFora + "'. Importe os jogos do dia pelo SofaScore primeiro,"
                        + " ou informe o confrontoId manualmente.");
            }
            confronto = achado.get();
        }

        MapeamentoMercado mapa = new MapeamentoMercado(res.clubeCasa, res.clubeFora);
        Instant agora = Instant.now();

        Relatorio rel = new Relatorio();
        rel.sucesso = true;
        rel.confrontoId = confronto.getId();
        rel.clubeCasa = res.clubeCasa;
        rel.clubeFora = res.clubeFora;
        rel.cotacoesLidas = res.cotacoes.size();
        rel.avisosParser.addAll(res.avisos);

        List<Odd> aGravar = new ArrayList<>();
        for (Bet365Parser.Cotacao c : res.cotacoes) {
            Optional<MapeamentoMercado.Traduzido> t = mapa.traduzir(c.mercado, c.linha, c.selecao);
            if (t.isEmpty()) {
                rel.naoMapeadas++;
                String motivo = mapa.explicar(c.mercado, c.linha, c.selecao).motivo();
                rel.motivos.merge(motivo, 1, Integer::sum);
                continue;
            }
            MapeamentoMercado.Traduzido tr = t.get();

            Odd o = new Odd();
            o.setConfronto(confronto);
            o.setMercado(tr.mercado());
            o.setLinha(tr.linha());
            o.setLinhaAte(tr.linhaAte());
            o.setSelecao(tr.selecao().name());
            o.setValor(c.odd);
            o.setCapturadoEm(agora);
            o.setOrigemMercado(c.mercado);
            o.setOrigemLinha(c.linha);
            o.setOrigemSelecao(c.selecao);
            aGravar.add(o);
        }

        oddRepo.saveAll(aGravar);
        rel.oddsGravadas = aGravar.size();
        rel.mensagem = rel.oddsGravadas + " odd(s) gravada(s) para " + confronto.descricao() + ".";
        return rel;
    }

    /**
     * Casa os nomes da bet365 com um confronto ja cadastrado.
     *
     * Reaproveita o ClubeNomes -- a bet365 escreve "Atlético Mineiro" onde o
     * SofaScore escreve "Atlético-MG", e essa e exatamente a classe de problema
     * que o resolverClube ja resolve. Nao reimplementar aqui evita duas
     * politicas de equivalencia divergindo com o tempo.
     */
    private Optional<Confronto> localizar(String nomeCasa, String nomeFora) {
        if (nomeCasa == null || nomeFora == null) return Optional.empty();

        List<Clube> todos = clubeRepo.findAll();
        Clube casa = ClubeNomes.melhorEquivalente(nomeCasa, todos);
        Clube fora = ClubeNomes.melhorEquivalente(nomeFora, todos);
        if (casa == null || fora == null) return Optional.empty();

        // Sem data no dump da bet365: pegamos o confronto AGENDADO mais proximo
        // desses dois clubes. Se houver mais de um, nao adivinhamos.
        List<Confronto> candidatos = confrontoRepo.findAll().stream()
                .filter(c -> c.getStatus() == Confronto.Status.AGENDADO)
                .filter(c -> c.getClubeCasa().getId().equals(casa.getId())
                        && c.getClubeFora().getId().equals(fora.getId()))
                .toList();

        return candidatos.size() == 1 ? Optional.of(candidatos.get(0)) : Optional.empty();
    }
}