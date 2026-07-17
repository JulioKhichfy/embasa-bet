package com.footballstats.service;

import com.footballstats.model.Nacao;
import com.footballstats.repository.NacaoRepository;
import com.footballstats.repository.PartidaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class NacaoService {
    private final NacaoRepository repo;
    private final PartidaRepository partidaRepo;
    public NacaoService(NacaoRepository repo, PartidaRepository partidaRepo) {
        this.repo = repo; this.partidaRepo = partidaRepo;
    }

    public List<Nacao> listar() { return repo.findAll(); }
    public Nacao buscar(Long id) { return repo.findById(id).orElseThrow(() -> new RuntimeException("Nação não encontrada")); }
    public Nacao salvar(Nacao n) { return repo.save(n); }
    public Nacao atualizar(Long id, Nacao dados) {
        Nacao n = buscar(id); n.setNome(dados.getNome()); return repo.save(n);
    }

    /**
     * Exclui a nacao. Primeiro remove todas as partidas de clubes desta nacao
     * (evita violacao de FK); em seguida a exclusao da nacao cascateia para
     * campeonatos e clubes (cascade/orphanRemoval ja configurado nas entidades).
     */
    @Transactional
    public void excluir(Long id) {
        partidaRepo.deleteAll(partidaRepo.findByNacao(id));
        repo.deleteById(id);
    }
}
