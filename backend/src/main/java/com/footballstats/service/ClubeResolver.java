package com.footballstats.service;

import com.footballstats.model.Campeonato;
import com.footballstats.model.Clube;
import com.footballstats.model.Nacao;
import com.footballstats.repository.CampeonatoRepository;
import com.footballstats.repository.ClubeRepository;
import com.footballstats.repository.NacaoRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * POLITICA UNICA de resolucao de identidade (nacao, campeonato, clube).
 *
 * Existia uma copia dessa logica dentro do PartidaService. Com a chegada da
 * importacao de confrontos e das odds, teriamos tres. Duas politicas de
 * equivalencia divergindo com o tempo e a receita para o mesmo clube existir
 * duas vezes na base -- exatamente o problema que a tela de fusao existe para
 * limpar. Uma so implementacao, um so comportamento.
 *
 * CASCATA DE RESOLUCAO DE CLUBE, na ordem:
 *
 *   1. ID EXTERNO. Identidade real da fonte. Se bate, acabou -- nenhum nome
 *      precisa ser comparado.
 *   2. NOME EXATO dentro do campeonato.
 *   3. APELIDO manual dentro do campeonato.
 *   4. CRIA NOVO.
 *
 * O passo 4 e deliberadamente burro: NAO tentamos similaridade textual para
 * fundir automaticamente. Um nome sem qualificador regional nunca deve se
 * fundir sozinho com uma variante qualificada -- o custo de uma fusao errada
 * (historico de dois clubes misturado, irreversivel na pratica) e muito maior
 * que o custo de um duplicado, que a tela de fusao resolve em dois cliques.
 *
 * AUTOCURA: quando resolvemos por nome/apelido um clube que ainda nao tinha id
 * externo, gravamos o id. Quando resolvemos por id um clube cujo nome recebido
 * e desconhecido, gravamos o nome como apelido. A base fica melhor a cada
 * importacao, sem intervencao manual.
 */
@Service
public class ClubeResolver {

    private final ClubeRepository clubeRepo;
    private final CampeonatoRepository campeonatoRepo;
    private final NacaoRepository nacaoRepo;

    public ClubeResolver(ClubeRepository clubeRepo, CampeonatoRepository campeonatoRepo,
                         NacaoRepository nacaoRepo) {
        this.clubeRepo = clubeRepo;
        this.campeonatoRepo = campeonatoRepo;
        this.nacaoRepo = nacaoRepo;
    }

    // ------------------------------------------------------------------
    // Nacao
    // ------------------------------------------------------------------

    public Nacao resolverNacao(String nome) {
        String n = (nome == null || nome.isBlank()) ? "Desconhecida" : nome.trim();
        return nacaoRepo.findByNomeIgnoreCase(n).orElseGet(() -> {
            Nacao nova = new Nacao();
            nova.setNome(n);
            return nacaoRepo.save(nova);
        });
    }

    // ------------------------------------------------------------------
    // Campeonato
    // ------------------------------------------------------------------

    /**
     * @param idExterno id do torneio na fonte; quando presente, manda
     * @param nome      nome exibido, ex. "Brasileirão Betano"
     * @param nomeNacao ex. "Brasil"
     */
    public Campeonato resolverCampeonato(Long idExterno, String nome, String nomeNacao) {
        if (idExterno != null) {
            Optional<Campeonato> porId = campeonatoRepo.findByIdExterno(idExterno);
            if (porId.isPresent()) {
                Campeonato c = porId.get();
                // Nome mudou (troca de patrocinador): atualizamos o rotulo e
                // mantemos o historico inteiro sob o mesmo campeonato.
                if (nome != null && !nome.isBlank() && !nome.equalsIgnoreCase(c.getNome())) {
                    c.setNome(nome.trim());
                    campeonatoRepo.save(c);
                }
                return c;
            }
        }

        Nacao nacao = resolverNacao(nomeNacao);
        String n = (nome == null || nome.isBlank()) ? "Desconhecido" : nome.trim();

        Optional<Campeonato> porNome = campeonatoRepo.findByNomeIgnoreCaseAndNacaoId(n, nacao.getId());
        if (porNome.isPresent()) {
            Campeonato c = porNome.get();
            if (c.getIdExterno() == null && idExterno != null) {
                c.setIdExterno(idExterno);
                campeonatoRepo.save(c);
            }
            return c;
        }

        Campeonato novo = new Campeonato();
        novo.setNome(n);
        novo.setNacao(nacao);
        novo.setIdExterno(idExterno);
        return campeonatoRepo.save(novo);
    }

    // ------------------------------------------------------------------
    // Clube
    // ------------------------------------------------------------------

    /** Resultado da resolucao, com o rastro do que aconteceu. */
    public record Resolucao(Clube clube, Via via, boolean criado) {
        public enum Via { ID_EXTERNO, NOME, APELIDO, PREFERIDO, NOVO }
    }

    /**
     * @param idExterno    id do clube na fonte; quando presente, tem precedencia
     * @param nome         nome vindo da fonte
     * @param campeonato   campeonato ao qual o clube pertence
     * @param preferidoId  escolha manual do usuario, quando houver; vence tudo
     */
    public Resolucao resolver(Long idExterno, String nome, Campeonato campeonato, Long preferidoId) {
        if (preferidoId != null) {
            Optional<Clube> pref = clubeRepo.findById(preferidoId);
            if (pref.isPresent()) {
                Clube c = pref.get();
                aprenderIdExterno(c, idExterno);
                aprenderApelido(c, nome);
                return new Resolucao(c, Resolucao.Via.PREFERIDO, false);
            }
        }

        // 1. identidade da fonte
        if (idExterno != null) {
            Optional<Clube> porId = clubeRepo.findByIdExterno(idExterno);
            if (porId.isPresent()) {
                Clube c = porId.get();
                aprenderApelido(c, nome);
                return new Resolucao(c, Resolucao.Via.ID_EXTERNO, false);
            }
        }

        String n = (nome == null) ? "" : nome.trim();

        // 2. nome exato no campeonato
        Optional<Clube> porNome = clubeRepo.findByNomeIgnoreCaseAndCampeonatoId(n, campeonato.getId());
        if (porNome.isPresent()) {
            Clube c = porNome.get();
            aprenderIdExterno(c, idExterno);
            return new Resolucao(c, Resolucao.Via.NOME, false);
        }

        // 3. apelido manual no campeonato
        List<Clube> doCampeonato = clubeRepo.findByCampeonatoId(campeonato.getId());
        for (Clube c : doCampeonato) {
            if (ClubeNomes.casaApelido(n, c.getApelidos())) {
                aprenderIdExterno(c, idExterno);
                return new Resolucao(c, Resolucao.Via.APELIDO, false);
            }
        }

        // 4. cria. Sem heuristica de similaridade: ver comentario da classe.
        Clube novo = new Clube();
        novo.setNome(n.isEmpty() ? "Desconhecido" : n);
        novo.setCampeonato(campeonato);
        novo.setIdExterno(idExterno);
        return new Resolucao(clubeRepo.save(novo), Resolucao.Via.NOVO, true);
    }

    // ------------------------------------------------------------------

    /**
     * Grava o id externo num clube que ainda nao tinha.
     *
     * Se o clube JA tem um id diferente, nao sobrescrevemos: sao duas entidades
     * distintas na fonte (time principal e sub-20, por exemplo) que por acaso
     * batem no nome. Sobrescrever misturaria dois historicos.
     */
    private void aprenderIdExterno(Clube c, Long idExterno) {
        if (idExterno == null || c.getIdExterno() != null) return;
        if (clubeRepo.findByIdExterno(idExterno).isPresent()) return;
        c.setIdExterno(idExterno);
        clubeRepo.save(c);
    }

    /**
     * Registra como apelido um nome que chegou junto de um id externo ja
     * conhecido. Seguro por construcao: a identidade veio do id, entao o nome
     * pertence a este clube com certeza.
     */
    private void aprenderApelido(Clube c, String nome) {
        if (nome == null || nome.isBlank()) return;
        String n = nome.trim();
        if (n.equalsIgnoreCase(c.getNome())) return;
        if (ClubeNomes.casaApelido(n, c.getApelidos())) return;
        c.getApelidos().add(n);
        clubeRepo.save(c);
    }
}