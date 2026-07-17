package com.footballstats.service;

import com.footballstats.dto.ImportCadastroDTO;
import com.footballstats.model.Campeonato;
import com.footballstats.model.Clube;
import com.footballstats.model.Nacao;
import com.footballstats.repository.CampeonatoRepository;
import com.footballstats.repository.ClubeRepository;
import com.footballstats.repository.NacaoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Importa cadastros a partir de um arquivo texto, uma linha por campeonato:
 *   nacao;campeonato;clube1;clube2;...;clubeN
 *
 * Idempotente: nacao/campeonato/clube ja existentes (por nome) sao reutilizados,
 * nao duplicados. Linhas em branco ou iniciadas por '#' sao ignoradas.
 */
@Service
public class ImportCadastroService {

    private final NacaoRepository nacaoRepo;
    private final CampeonatoRepository campRepo;
    private final ClubeRepository clubeRepo;

    public ImportCadastroService(NacaoRepository nacaoRepo, CampeonatoRepository campRepo, ClubeRepository clubeRepo) {
        this.nacaoRepo = nacaoRepo;
        this.campRepo = campRepo;
        this.clubeRepo = clubeRepo;
    }

    @Transactional
    public ImportCadastroDTO importar(String conteudo) {
        ImportCadastroDTO r = new ImportCadastroDTO();
        String[] linhas = conteudo.replace("\r\n", "\n").replace("\r", "\n").split("\n");

        int numLinha = 0;
        for (String bruta : linhas) {
            numLinha++;
            String linha = bruta.trim();
            if (linha.isEmpty() || linha.startsWith("#")) continue;

            String[] partes = linha.split(";");
            if (partes.length < 2) {
                r.linhasIgnoradas++;
                r.avisos.add("Linha " + numLinha + ": formato inválido (esperado nacao;campeonato;clubes). Ignorada.");
                continue;
            }

            String nomeNacao = partes[0].trim();
            String nomeCamp = partes[1].trim();
            if (nomeNacao.isEmpty() || nomeCamp.isEmpty()) {
                r.linhasIgnoradas++;
                r.avisos.add("Linha " + numLinha + ": nação ou campeonato vazio. Ignorada.");
                continue;
            }

            Nacao nacao = obterOuCriarNacao(nomeNacao, r);
            Campeonato camp = obterOuCriarCampeonato(nomeCamp, nacao, r);

            for (int i = 2; i < partes.length; i++) {
                String nomeClube = partes[i].trim();
                if (nomeClube.isEmpty()) continue;
                obterOuCriarClube(nomeClube, camp, r);
            }
            r.linhasProcessadas++;
        }

        r.mensagem = String.format(
            "Importação concluída: %d linha(s), %d nação(ões), %d campeonato(s), %d clube(s) criados.",
            r.linhasProcessadas, r.nacoesCriadas, r.campeonatosCriados, r.clubesCriados);
        return r;
    }

    private Nacao obterOuCriarNacao(String nome, ImportCadastroDTO r) {
        Optional<Nacao> ex = nacaoRepo.findByNomeIgnoreCase(nome);
        if (ex.isPresent()) return ex.get();
        Nacao n = new Nacao();
        n.setNome(nome);
        n = nacaoRepo.save(n);
        r.nacoesCriadas++;
        return n;
    }

    private Campeonato obterOuCriarCampeonato(String nome, Nacao nacao, ImportCadastroDTO r) {
        Optional<Campeonato> ex = campRepo.findByNomeIgnoreCaseAndNacaoId(nome, nacao.getId());
        if (ex.isPresent()) return ex.get();
        Campeonato c = new Campeonato();
        c.setNome(nome);
        c.setNacao(nacao);
        c = campRepo.save(c);
        r.campeonatosCriados++;
        return c;
    }

    private void obterOuCriarClube(String nome, Campeonato camp, ImportCadastroDTO r) {
        Optional<Clube> ex = clubeRepo.findByNomeIgnoreCaseAndCampeonatoId(nome, camp.getId());
        if (ex.isPresent()) return;
        Clube c = new Clube();
        c.setNome(nome);
        c.setCampeonato(camp);
        clubeRepo.save(c);
        r.clubesCriados++;
    }
}
