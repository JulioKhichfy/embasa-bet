package com.footballstats.service;

import com.footballstats.model.Campeonato;
import com.footballstats.model.Nacao;
import com.footballstats.repository.CampeonatoRepository;
import com.footballstats.repository.ClubeRepository;
import com.footballstats.repository.NacaoRepository;
import com.footballstats.repository.PartidaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class CampeonatoService {
    private final CampeonatoRepository repo;
    private final NacaoRepository nacaoRepo;
    private final ClubeRepository clubeRepo;
    private final PartidaRepository partidaRepo;
    public CampeonatoService(CampeonatoRepository repo, NacaoRepository nacaoRepo,
                             ClubeRepository clubeRepo, PartidaRepository partidaRepo) {
        this.repo = repo; this.nacaoRepo = nacaoRepo;
        this.clubeRepo = clubeRepo; this.partidaRepo = partidaRepo;
    }

    public List<Campeonato> listar() { return repo.findAll(); }
    public List<Campeonato> porNacao(Long nacaoId) { return repo.findByNacaoId(nacaoId); }
    public Campeonato buscar(Long id) { return repo.findById(id).orElseThrow(() -> new RuntimeException("Campeonato não encontrado")); }

    public Campeonato salvar(Long nacaoId, Campeonato c) {
        Nacao n = nacaoRepo.findById(nacaoId).orElseThrow(() -> new RuntimeException("Nação não encontrada"));
        c.setNacao(n);
        return repo.save(c);
    }
    public Campeonato atualizar(Long id, Campeonato dados) {
        Campeonato c = buscar(id);
        c.setNome(dados.getNome());
        if (dados.getNacao() != null && dados.getNacao().getId() != null) {
            c.setNacao(nacaoRepo.findById(dados.getNacao().getId()).orElse(c.getNacao()));
        }
        return repo.save(c);
    }
    /** Exclui o campeonato: remove partidas dos seus clubes, depois os clubes, depois o campeonato. */
    @Transactional
    public void excluir(Long id) {
        partidaRepo.deleteAll(partidaRepo.findByCampeonato(id));
        clubeRepo.deleteAll(clubeRepo.findByCampeonatoId(id));
        repo.deleteById(id);
    }
}
