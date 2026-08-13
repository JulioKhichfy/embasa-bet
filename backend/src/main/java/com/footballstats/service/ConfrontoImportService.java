package com.footballstats.service;

import com.footballstats.model.Campeonato;
import com.footballstats.model.Clube;
import com.footballstats.model.Confronto;
import com.footballstats.parser.SofaScoreParser;
import com.footballstats.repository.ConfrontoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Cria CONFRONTOS a partir de dumps de pagina de jogo do SofaScore.
 *
 * Fecha o circuito: sem confronto nao ha onde pendurar odd nem projecao.
 *
 * POR QUE PAGINA DE JOGO E NAO LISTAGEM DO DIA
 * --------------------------------------------
 * Reaproveita exatamente o pipeline que ja funciona -- abrir abas, despejar
 * HTML com a extensao, importar. Nao inventa um segundo formato de coleta, nao
 * exige um segundo parser, e o mesmo arquivo serve duas vezes: antes do jogo
 * vira Confronto, depois do jogo vira Partida com estatisticas. Uma listagem
 * traria mais jogos por clique, mas so nomes e horarios: sem arbitro, sem id
 * externo, sem a media de cartoes -- e teria que ser reconciliada depois.
 *
 * IDEMPOTENTE por (data, casa, fora): reimportar o mesmo jogo ATUALIZA os
 * campos que costumam chegar depois (arbitro escalado, horario remarcado) em
 * vez de duplicar. Confronto ja encerrado nao e mexido: o passado nao muda, e
 * sobrescrever apagaria o contexto sob o qual as projecoes foram feitas.
 */
@Service
public class ConfrontoImportService {

    private final ConfrontoRepository confrontoRepo;
    private final ClubeResolver resolver;
    private final SofaScoreParser parser;

    public ConfrontoImportService(ConfrontoRepository confrontoRepo, ClubeResolver resolver,
                                  SofaScoreParser parser) {
        this.confrontoRepo = confrontoRepo;
        this.resolver = resolver;
        this.parser = parser;
    }

    public static class Relatorio {
        public boolean sucesso;
        public String arquivo;
        public String mensagem;
        public Long confrontoId;
        public boolean criado;
        public String campeonato;
        public String clubeCasa;
        public String clubeFora;
        public String arbitro;
        /** clubes que a resolucao teve de CRIAR -- candidatos a fusao manual */
        public List<String> clubesNovos = new ArrayList<>();
        public List<String> avisos = new ArrayList<>();

        static Relatorio falha(String arquivo, String msg) {
            Relatorio r = new Relatorio();
            r.sucesso = false;
            r.arquivo = arquivo;
            r.mensagem = msg;
            return r;
        }
    }

    @Transactional
    public Relatorio importar(String html, String nomeArquivo) {
        SofaScoreParser.Resultado res;
        try {
            res = parser.parse(html);
        } catch (Exception e) {
            return Relatorio.falha(nomeArquivo, "Falha ao ler o HTML: " + e.getMessage());
        }

        if (res.nomeClubeCasa == null || res.nomeClubeFora == null) {
            return Relatorio.falha(nomeArquivo, "Não consegui identificar os clubes na página.");
        }
        if (res.data == null) {
            return Relatorio.falha(nomeArquivo, "Não consegui identificar a data da partida.");
        }

        Relatorio rel = new Relatorio();
        rel.arquivo = nomeArquivo;
        rel.avisos.addAll(res.avisos);

        Campeonato camp = resolver.resolverCampeonato(res.campeonatoIdExterno, res.campeonato, res.nacao);
        rel.campeonato = camp.getNome();

        ClubeResolver.Resolucao rc = resolver.resolver(res.idExternoCasa, res.nomeClubeCasa, camp, null);
        ClubeResolver.Resolucao rf = resolver.resolver(res.idExternoFora, res.nomeClubeFora, camp, null);
        Clube casa = rc.clube(), fora = rf.clube();
        rel.clubeCasa = casa.getNome();
        rel.clubeFora = fora.getNome();
        if (rc.criado()) rel.clubesNovos.add(casa.getNome());
        if (rf.criado()) rel.clubesNovos.add(fora.getNome());

        if (casa.getId().equals(fora.getId())) {
            return Relatorio.falha(nomeArquivo, "Mandante e visitante resolveram para o mesmo clube ("
                    + casa.getNome() + "). Ajuste os apelidos antes de importar.");
        }

        Optional<Confronto> existente = confrontoRepo
                .findByDataAndClubeCasaIdAndClubeForaId(res.data, casa.getId(), fora.getId());

        Confronto c;
        if (existente.isPresent()) {
            c = existente.get();
            rel.criado = false;
            if (c.getStatus() == Confronto.Status.ENCERRADO) {
                rel.sucesso = true;
                rel.confrontoId = c.getId();
                rel.arbitro = c.getArbitro();
                rel.mensagem = "Confronto já encerrado; nada foi alterado.";
                return rel;
            }
        } else {
            c = new Confronto();
            c.setData(res.data);
            c.setClubeCasa(casa);
            c.setClubeFora(fora);
            rel.criado = true;
        }

        aplicar(c, res);
        c = confrontoRepo.save(c);

        rel.sucesso = true;
        rel.confrontoId = c.getId();
        rel.arbitro = c.getArbitro();
        rel.mensagem = (rel.criado ? "Confronto criado: " : "Confronto atualizado: ") + c.descricao();
        if (c.getArbitro() == null) {
            rel.avisos.add("Árbitro ainda não divulgado; reimporte mais perto do jogo"
                    + " — sem ele o mercado de cartões trabalha só com a média dos clubes.");
        }
        return rel;
    }

    /**
     * Copia o que o parser trouxe, SEM apagar o que ja existia.
     *
     * Paginas pre-jogo costumam nao ter arbitro. Se o import de tres dias antes
     * gravou o arbitro e o de hoje vem sem, sobrescrever com null perderia
     * informacao boa. So preenchemos campo vazio ou trocamos por valor presente.
     */
    private void aplicar(Confronto c, SofaScoreParser.Resultado res) {
        if (res.hora != null) {
            try { c.setHora(LocalTime.parse(res.hora)); } catch (Exception ignored) { }
        }
        if (res.arbitro != null && !res.arbitro.isBlank()) c.setArbitro(res.arbitro);
        if (res.arbitroMediaAmarelos != null)  c.setArbitroMediaAmarelos(res.arbitroMediaAmarelos);
        if (res.arbitroMediaVermelhos != null) c.setArbitroMediaVermelhos(res.arbitroMediaVermelhos);

        // A propria pagina diz se o jogo acabou: placar preenchido com
        // estatisticas coletadas so acontece depois do apito final.
        boolean encerrado = !res.casa.isEmpty()
                && (res.golsCasa > 0 || res.golsFora > 0 || res.casa.getOrDefault("finalizacoes", 0f) > 0);
        c.setStatus(encerrado ? Confronto.Status.ENCERRADO : Confronto.Status.AGENDADO);
    }
}