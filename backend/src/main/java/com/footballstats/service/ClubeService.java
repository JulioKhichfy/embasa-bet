package com.footballstats.service;

import com.footballstats.dto.DuplicadoSugestaoDTO;
import com.footballstats.model.Campeonato;
import com.footballstats.model.Clube;
import com.footballstats.repository.CampeonatoRepository;
import com.footballstats.repository.ClubeRepository;
import com.footballstats.repository.PartidaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class ClubeService {
    private final ClubeRepository repo;
    private final CampeonatoRepository campRepo;
    private final PartidaRepository partidaRepo;

    public ClubeService(ClubeRepository repo, CampeonatoRepository campRepo, PartidaRepository partidaRepo) {
        this.repo = repo; this.campRepo = campRepo; this.partidaRepo = partidaRepo;
    }

    public List<Clube> listar() { return repo.findAll(); }
    public List<Clube> porCampeonato(Long campId) { return repo.findByCampeonatoId(campId); }
    public Clube buscar(Long id) { return repo.findById(id).orElseThrow(() -> new RuntimeException("Clube não encontrado")); }

    public Clube salvar(Long campeonatoId, Clube c) {
        Campeonato camp = campRepo.findById(campeonatoId).orElseThrow(() -> new RuntimeException("Campeonato não encontrado"));
        c.setCampeonato(camp);
        return repo.save(c);
    }
    public Clube atualizar(Long id, Clube dados) {
        Clube c = buscar(id);
        c.setNome(dados.getNome());
        if (dados.getCampeonato() != null && dados.getCampeonato().getId() != null) {
            c.setCampeonato(campRepo.findById(dados.getCampeonato().getId()).orElse(c.getCampeonato()));
        }
        return repo.save(c);
    }

    /**
     * Exclui o clube. Como PARTIDA referencia CLUBE (casa/fora), primeiro
     * removemos todas as partidas em que o clube participou (o que remove
     * tambem suas ESTATISTICAs, via cascade em Partida). So entao apagamos
     * o clube, evitando a violacao de integridade referencial.
     */
    @Transactional
    public void excluir(Long id) {
        partidaRepo.deleteAll(partidaRepo.findByClube(id));
        repo.deleteById(id);
    }

    /** Quantas partidas seriam removidas junto com o clube (para aviso no front). */
    public int contarPartidas(Long id) {
        return partidaRepo.findByClube(id).size();
    }

    /**
     * Sugere pares de clubes que provavelmente sao o mesmo
     * ("Vasco" / "Vasco da Gama"), para que o usuario possa fundi-los.
     * Compara apenas clubes do mesmo campeonato.
     */
    public List<DuplicadoSugestaoDTO> sugerirDuplicados(Long campeonatoId) {
        List<Clube> clubes = (campeonatoId != null) ? repo.findByCampeonatoId(campeonatoId) : repo.findAll();

        List<DuplicadoSugestaoDTO> out = new ArrayList<>();
        for (int i = 0; i < clubes.size(); i++) {
            for (int j = i + 1; j < clubes.size(); j++) {
                Clube a = clubes.get(i), b = clubes.get(j);

                Long ca = a.getCampeonato() != null ? a.getCampeonato().getId() : null;
                Long cb = b.getCampeonato() != null ? b.getCampeonato().getId() : null;
                if (ca == null || cb == null || !ca.equals(cb)) continue;

                if (!ClubeNomes.equivalentes(a.getNome(), b.getNome())) continue;

                DuplicadoSugestaoDTO d = new DuplicadoSugestaoDTO();
                d.clubeAId = a.getId();   d.clubeANome = a.getNome();   d.partidasA = contarPartidas(a.getId());
                d.clubeBId = b.getId();   d.clubeBNome = b.getNome();   d.partidasB = contarPartidas(b.getId());
                d.campeonatoNome = a.getCampeonato().getNome();
                out.add(d);
            }
        }
        return out;
    }
}
