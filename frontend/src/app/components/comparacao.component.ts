import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ClubeDetalhe, PartidaResumo, Ranking, RankingQuesito } from '../models/entities.model';
import { PartidaService } from '../services/partida.service';
import { ClubeService } from '../services/clube.service';
import { STAT_FIELDS, CATEGORIAS, StatMeta } from '../models/sofascore.model';
import { MODELOS, ModeloId, ResultadoModelo, calcular, PARAMS_PADRAO } from '../models/probabilidade.model';

/**
 * Estado de um clube na comparação (casa ou fora).
 *
 * O filtro TODOS/CASA/FORA é POR LADO e INDEPENDENTE: cada clube recorta as
 * SUAS partidas sem mexer no outro lado. É o que permite a pergunta que motiva
 * a tela — "como o mandante se comporta em casa contra como o visitante se
 * comporta fora" — que um filtro global torna impossível de fazer.
 *
 * O ranking e a posição na tabela ficam FIXOS em TODOS de propósito: são
 * propriedades do campeonato, não do recorte de cada lado. Filtrá-los junto
 * produziria uma "tabela só de jogos em casa", que não é a classificação real
 * e induziria a erro na leitura.
 */
interface LadoClube {
  clubeId: number;
  nome: string;
  filtro: string;                // 'TODOS' | 'CASA' | 'FORA' — só deste lado
  detalhe?: ClubeDetalhe;        // partidas já recortadas pelo filtro deste lado
  limiteMedia: number;           // N do select próprio da média/quadro
  abertos: Set<number>;          // partidaIds com accordion aberto
  // desativados[partidaId] = Set de campos desativados naquela partida
  desativados: Map<number, Set<string>>;
}

/**
 * Uma linha da classificação montada nesta tela.
 *
 * A tabela é construída no cliente a partir do detalhe de cada clube, igual ao
 * dashboard faz. Assim ela obedece ao mesmo recorte (últimos N + CASA/FORA) que
 * o resto da tela, coisa que um endpoint de classificação pronto não daria sem
 * receber os mesmos parâmetros.
 */
interface LinhaTabela {
  clubeId: number;
  nome: string;
  jogos: number;
  pontos: number;
  v: number; e: number; d: number;
  gf: number; gs: number; sg: number;
}

/** Uma linha do quadro de probabilidades (mercado + 3 visões). */
interface LinhaPrev {
  rotulo: string;
  conservadora: string;
  moderada: string;
  arrojada: string;
}

@Component({
  selector: 'app-comparacao',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
  <div class="head">
    <button class="btn-ghost" (click)="voltar()">← Voltar</button>
    <h1>Comparação</h1>
    <span class="spacer"></span>
    <label class="lim">Buscar últimas
      <input type="number" min="1" [ngModel]="limiteBusca" (ngModelChange)="onLimiteBusca($event)" style="width:64px">
      partidas
    </label>
  </div>

  <!-- Posições na tabela (segundo o nº de jogos do recorte atual) -->
  <div *ngIf="casa.detalhe && fora.detalhe" class="posbar card">
    <span class="posItem">
      <span class="posN cCasa">{{ posicaoTexto(casa.clubeId) }}</span>
      <span class="posNome cCasa">{{ casa.nome }}</span>
    </span>
    <span class="posMid muted mini">posição na tabela (todos os jogos, últimos {{ limiteBusca }}) · {{ totalClubesRanking() }} clubes</span>
    <span class="posItem right">
      <span class="posNome cFora">{{ fora.nome }}</span>
      <span class="posN cFora">{{ posicaoTexto(fora.clubeId) }}</span>
    </span>
  </div>

  <div class="split">
    <!-- LADO ESQUERDO: casa (a) -->
    <div class="lado">
      <ng-container *ngTemplateOutlet="painel; context: { l: casa, cor: 'casa' }"></ng-container>
    </div>
    <!-- LADO DIREITO: fora (b) -->
    <div class="lado">
      <ng-container *ngTemplateOutlet="painel; context: { l: fora, cor: 'fora' }"></ng-container>
    </div>
  </div>

  <!-- ============ ANÁLISE / PROBABILIDADES ============ -->
  <div *ngIf="casa.detalhe && fora.detalhe" class="card sec analise">
    <div class="anHd">
      <h2>Análise da partida (baseada nas médias)</h2>
      <span class="spacer"></span>
      <span class="pill">{{ casa.nome }} × {{ fora.nome }}</span>
    </div>
    <p class="muted mini">
      Estimativas derivadas das médias atuais de cada clube (N por lado no seletor de cada painel).
      <span class="recorte">{{ recorte() }}</span>
      Modelo probabilístico simples (Poisson) para fins de referência — não é garantia de resultado.
      <b>Casa = {{ casa.nome }}</b>, <b>Fora = {{ fora.nome }}</b>.
    </p>

    <!-- Placar/força estimados -->
    <div class="expGrid">
      <div class="expCell">
        <small class="cCasa">{{ casa.nome }} (esperado)</small>
        <b>{{ xgCasa() | number:'1.2-2' }}</b><span class="mini muted">gols esperados</span>
      </div>
      <div class="expCell mid">
        <small class="muted">Placar mais provável</small>
        <b class="placar">{{ placarProvavel().casa }} × {{ placarProvavel().fora }}</b>
      </div>
      <div class="expCell">
        <small class="cFora">{{ fora.nome }} (esperado)</small>
        <b>{{ xgFora() | number:'1.2-2' }}</b><span class="mini muted">gols esperados</span>
      </div>
    </div>

    <!-- ======== SELETOR DE MODELO ======== -->
    <div class="modeloBox">
      <div class="secHd">
        <h3>Modelo probabilístico</h3>
        <span class="recorte">{{ recorte() }}</span>
        <span class="spacer"></span>
        <button class="btn-ghost expBtn" (click)="mostrarParams = !mostrarParams">
          {{ mostrarParams ? 'Ocultar ajustes' : '⚙ Ajustes' }}
        </button>
      </div>
      <div class="radios">
        <label *ngFor="let m of modelos" class="radio" [class.on]="modeloSel === m.id">
          <input type="radio" name="modelo" [value]="m.id"
                 [checked]="modeloSel === m.id" (change)="setModelo(m.id)">
          <span class="rNome">{{ m.nome }}</span>
        </label>
      </div>
      <p class="muted mini desc">{{ descricaoModelo() }}</p>

      <!-- parâmetros do modelo -->
      <div class="params" *ngIf="mostrarParams">
        <label class="pRow" [class.dim]="modeloSel !== 'dixoncoles'">
          <span>ρ (Dixon-Coles)</span>
          <input type="number" step="0.01" min="-0.30" max="0.10" [(ngModel)]="paramRho">
          <small class="muted">negativo aumenta 0-0 e 1-1</small>
        </label>
        <label class="pRow" [class.dim]="modeloSel !== 'bivariate'">
          <span>λ₃ (covariância)</span>
          <input type="number" step="0.05" min="0" max="1" [(ngModel)]="paramL3">
          <small class="muted">maior = jogos mais correlacionados</small>
        </label>
        <label class="pRow" [class.dim]="modeloSel !== 'negbin'">
          <span>r (dispersão)</span>
          <input type="number" step="1" min="1" max="50" [(ngModel)]="paramR">
          <small class="muted">menor = cauda mais pesada</small>
        </label>
        <button class="btn-ghost expBtn" (click)="resetParams()">Restaurar padrões</button>
      </div>

      <!-- comparativo entre modelos -->
      <table class="modTab">
        <thead>
          <tr>
            <th>Modelo</th>
            <th class="c">1</th><th class="c">X</th><th class="c">2</th>
            <th class="c">BTTS</th><th class="c">Placar</th><th class="c">O2.5</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let t of todosModelos()" [class.ativo]="t.info.id === modeloSel"
              (click)="setModelo(t.info.id)">
            <td class="mNome">{{ t.info.nome }}</td>
            <td class="c">{{ t.r.vCasa | number:'1.0-1' }}%</td>
            <td class="c">{{ t.r.empate | number:'1.0-1' }}%</td>
            <td class="c">{{ t.r.vFora | number:'1.0-1' }}%</td>
            <td class="c">{{ t.r.bttsSim | number:'1.0-1' }}%</td>
            <td class="c">{{ t.r.placarCasa }}×{{ t.r.placarFora }}</td>
            <td class="c">{{ t.r.over['2.5'] | number:'1.0-1' }}%</td>
          </tr>
        </tbody>
      </table>
      <p class="muted mini">
        Clique numa linha para adotar o modelo. Modelos diferentes discordam — a divergência entre eles
        é, ela própria, uma medida da incerteza da estimativa.
      </p>
    </div>

    <!-- 1X2 -->
    <h3>Resultado (1X2) <span class="pill">{{ descricaoModeloNome() }}</span>
      <span class="recorte">{{ recorte() }}</span></h3>
    <div class="probBars">
      <div class="pb">
        <span class="pbLbl cCasa">Vitória {{ casa.nome }}</span>
        <div class="pbTrack"><div class="pbFill casa" [style.width.%]="probs().vCasa"></div></div>
        <span class="pbPct">{{ probs().vCasa | number:'1.0-0' }}%</span>
      </div>
      <div class="pb">
        <span class="pbLbl">Empate</span>
        <div class="pbTrack"><div class="pbFill draw" [style.width.%]="probs().empate"></div></div>
        <span class="pbPct">{{ probs().empate | number:'1.0-0' }}%</span>
      </div>
      <div class="pb">
        <span class="pbLbl cFora">Vitória {{ fora.nome }}</span>
        <div class="pbTrack"><div class="pbFill fora" [style.width.%]="probs().vFora"></div></div>
        <span class="pbPct">{{ probs().vFora | number:'1.0-0' }}%</span>
      </div>
    </div>

    <!-- Ambos marcam / Chance dupla -->
    <div class="miniGrid">
      <div class="miniCard">
        <h4>Ambos marcam (BTTS)</h4>
        <div class="btts">
          <span class="tag sim">Sim {{ probs().bttsSim | number:'1.0-0' }}%</span>
          <span class="tag nao">Não {{ probs().bttsNao | number:'1.0-0' }}%</span>
        </div>
      </div>
      <div class="miniCard">
        <h4>Total de gols (over)</h4>
        <div class="dc">
          <span class="tag" *ngFor="let l of linhasOver">
            Mais de {{ l }} gols: <b>{{ probs().over[l] | number:'1.0-1' }}%</b>
          </span>
        </div>
      </div>
      <div class="miniCard">
        <h4>Chance dupla</h4>
        <div class="dc">
          <span class="tag">{{ casa.nome }} ou Empate: <b>{{ probs().dc1X | number:'1.0-0' }}%</b></span>
          <span class="tag">{{ casa.nome }} ou {{ fora.nome }}: <b>{{ probs().dc12 | number:'1.0-0' }}%</b></span>
          <span class="tag">{{ fora.nome }} ou Empate: <b>{{ probs().dcX2 | number:'1.0-0' }}%</b></span>
        </div>
      </div>
    </div>

    <!-- ======== CLASSIFICAÇÃO DO CAMPEONATO ======== -->
    <div class="dadosBox">
      <div class="secHd">
        <h3>Classificação<span *ngIf="campeonatoNome"> — {{ campeonatoNome }}</span></h3>
        <span class="spacer"></span>
        <label class="lim mini">últimos
          <input type="number" min="1" [ngModel]="limiteTabela"
                 (ngModelChange)="onLimiteTabela($event)" style="width:56px">
          jogos
        </label>
        <div class="fbtns">
          <button *ngFor="let f of filtros" class="fbtn" [class.on]="filtroTabela===f"
                  (click)="setFiltroTabela(f)">{{ f }}</button>
        </div>
      </div>

      <p class="muted mini" *ngIf="filtroTabela === 'TODOS'">
        Pontuação considerando apenas as últimas {{ limiteTabela }} partidas de cada clube.
        Os dois clubes em comparação aparecem destacados.
      </p>
      <p class="aviso mini" *ngIf="filtroTabela !== 'TODOS'">
        <b>Isto não é a classificação oficial.</b> É a tabela que sairia se só valessem os
        jogos {{ filtroTabela === 'CASA' ? 'em casa' : 'fora' }} — útil para ver quem depende
        do mando, mas não corresponde a nenhuma tabela real. Clubes com número diferente de
        jogos nesse recorte não são diretamente comparáveis por pontos.
      </p>

      <div *ngIf="carregandoTabela" class="muted mini">montando tabela…</div>
      <div *ngIf="!carregandoTabela && !tabela.length" class="muted mini">
        Sem partidas para este recorte.
      </div>

      <table class="clasTab" *ngIf="tabela.length">
        <thead>
          <tr>
            <th class="c">#</th><th>Clube</th>
            <th class="c">J</th><th class="c">P</th>
            <th class="c">V</th><th class="c">E</th><th class="c">D</th>
            <th class="c">GF</th><th class="c">GS</th><th class="c">SG</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let ln of tabela; let i = index"
              [class.tCasa]="ln.clubeId === casa.clubeId"
              [class.tFora]="ln.clubeId === fora.clubeId">
            <td class="c pos">{{ i + 1 }}</td>
            <td class="qn">{{ ln.nome }}</td>
            <td class="c muted">{{ ln.jogos }}</td>
            <td class="c pts"><b>{{ ln.pontos }}</b></td>
            <td class="c">{{ ln.v }}</td>
            <td class="c">{{ ln.e }}</td>
            <td class="c">{{ ln.d }}</td>
            <td class="c muted">{{ ln.gf }}</td>
            <td class="c muted">{{ ln.gs }}</td>
            <td class="c" [class.sgPos]="ln.sg > 0" [class.sgNeg]="ln.sg < 0">
              {{ ln.sg > 0 ? '+' : '' }}{{ ln.sg }}
            </td>
          </tr>
        </tbody>
      </table>
      <p class="muted mini" *ngIf="tabela.length">
        Ordenação: pontos → vitórias → saldo → gols feitos → nome.
      </p>
    </div>

    <!-- ======== DADOS DOS CLUBES (ranking global) ======== -->
    <div class="dadosBox">
      <div class="secHd">
        <h3>Dados dos clubes</h3>
        <span class="spacer"></span>
        <div class="fbtns">
          <button *ngFor="let f of filtros" class="fbtn" [class.on]="filtroRanking===f"
                  (click)="setFiltroRanking(f)">{{ f }}</button>
        </div>
        <button class="btn-ghost expBtn" (click)="rankingExpandido = !rankingExpandido">
          {{ rankingExpandido ? 'Ver só os comparados' : 'Ver ranking geral' }}
        </button>
      </div>
      <p class="muted mini">
        Ranking dos clubes <b>do campeonato</b>, últimas {{ limiteBusca }} partidas, recorte
        <b>{{ filtroRanking }}</b>. Este filtro é <b>independente</b> dos painéis de cada clube.
        <b>T</b> = total somado · <b>M</b> = média por partida. Linhas destacadas são os clubes em comparação.
        Para <b>Posição na tabela</b>, T = pontos e M = pontos por jogo.
      </p>

      <div *ngIf="!ranking" class="muted mini">carregando ranking…</div>

      <div class="qGrid" *ngIf="ranking">
        <div class="qCard" *ngFor="let q of ranking.quesitos">
          <h4>{{ q.rotulo }}</h4>
          <table class="qTab">
            <thead>
              <tr><th class="c">#</th><th>Clube</th><th class="c">T</th><th class="c">M</th></tr>
            </thead>
            <tbody>
              <tr *ngFor="let it of (rankingExpandido ? topN(q, 10) : itensComparados(q))"
                  [class.destaque]="ehComparado(it.clubeId)">
                <td class="c pos">{{ it.pos }}º</td>
                <td class="qn" [title]="it.campeonatoNome">{{ it.clubeNome }}</td>
                <td class="c t">{{ it.total | number:'1.0-0' }}</td>
                <td class="c m">{{ it.media | number:'1.2-2' }}</td>
              </tr>
              <tr *ngIf="!(rankingExpandido ? topN(q, 10) : itensComparados(q)).length">
                <td colspan="4" class="muted center mini">sem dados</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- Totais previstos: conservadora / moderada / arrojada -->
    <h3>Previsões de totais na partida <span class="recorte">{{ recorte() }}</span></h3>
    <p class="muted mini">
      <b>Conservadora</b> = mínima (visão prudente) · <b>Moderada</b> = cenário médio · <b>Arrojada</b> = um nível acima.
    </p>
    <table class="prevTab">
      <thead>
        <tr>
          <th>Mercado</th>
          <th class="center">Conservadora</th>
          <th class="center">Moderada</th>
          <th class="center">Arrojada</th>
        </tr>
      </thead>
      <tbody>
        <tr *ngFor="let ln of previsoes()">
          <td>{{ ln.rotulo }}</td>
          <td class="center cons">{{ ln.conservadora }}</td>
          <td class="center mod">{{ ln.moderada }}</td>
          <td class="center arr">{{ ln.arrojada }}</td>
        </tr>
      </tbody>
    </table>
    <p class="muted mini disc">
      ⚠ Valores estimados a partir de médias históricas com o modelo interno do app. Use como apoio, não como certeza.
    </p>
  </div>

  <!-- Template reutilizável para cada lado -->
  <ng-template #painel let-l="l" let-cor="cor">
    <div *ngIf="l.detalhe" class="card sec">
      <div class="ladoHd">
        <h2 [class.cCasa]="cor==='casa'" [class.cFora]="cor==='fora'">{{ l.nome }}</h2>
        <span class="spacer"></span>
        <div class="fbtns lado" [class.bCasa]="cor==='casa'" [class.bFora]="cor==='fora'"
             [title]="'Filtro de ' + l.nome + ' — afeta apenas este painel'">
          <button *ngFor="let f of filtros" class="fbtn" [class.on]="l.filtro===f"
                  (click)="setFiltro(l, f)">{{ f }}</button>
        </div>
      </div>

      <div class="secHd">
        <h3>Últimas {{ l.detalhe.partidas.length }} partidas</h3>
        <span class="spacer"></span>
        <span class="pill">{{ l.filtro }}</span>
      </div>
      <div class="acc" *ngFor="let p of l.detalhe.partidas">
        <div class="acc-hd" (click)="toggleAccordion(l, p.partidaId)">
          <span class="chev">{{ l.abertos.has(p.partidaId) ? '▾' : '▸' }}</span>
          <span class="dt">{{ p.data }}</span>
          <span class="adv">vs {{ p.adversario }}</span>
          <span class="pill">{{ p.emCasa ? 'Casa' : 'Fora' }}</span>
          <span class="spacer"></span>
          <span class="plc">{{ p.golsFeitos }}×{{ p.golsSofridos }}</span>
          <span class="badge" [ngClass]="p.resultado.toLowerCase()">{{ p.resultado }}</span>
        </div>
        <div class="acc-bd" *ngIf="l.abertos.has(p.partidaId)">
          <p class="muted mini">Clique no 👁 para incluir/excluir o item do cálculo da média (apenas nesta partida).</p>
          <!-- cabeçalho clube × adversário -->
          <div class="cabecPar">
            <span class="lado clube">{{ l.nome }}</span>
            <span class="muted center">×</span>
            <span class="lado adv">{{ p.adversario }}</span>
          </div>
          <div *ngFor="let cat of categorias">
            <h4>{{ cat }}</h4>
            <div class="ln" *ngFor="let m of camposPorCategoria(cat)"
                 [class.off]="estaDesativado(l, p.partidaId, m.campo)">
              <button class="eye" (click)="toggleCampo(l, p.partidaId, m.campo)"
                      [title]="estaDesativado(l, p.partidaId, m.campo) ? 'Incluir no cálculo' : 'Excluir do cálculo'">
                {{ estaDesativado(l, p.partidaId, m.campo) ? '🚫' : '👁' }}
              </button>
              <span class="vClube" [class.win]="valPartida(p, m.campo) > valAdvPartida(p, m.campo)">
                {{ valPartida(p, m.campo) | number:'1.0-2' }}
              </span>
              <span class="rot">{{ m.rotulo }}</span>
              <span class="spacer"></span>
              <span class="vAdv" [class.win]="valAdvPartida(p, m.campo) > valPartida(p, m.campo)">
                {{ valAdvPartida(p, m.campo) | number:'1.0-2' }}
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- ======== QUADRO COMPARATIVO POR PARTIDA ======== -->
      <div class="quadroBox">
        <div class="secHd">
          <h3>Quadro por partida</h3>
          <span class="spacer"></span>
          <label class="chk" title="Mostrar também os valores do adversário em cada partida">
            <input type="checkbox" [(ngModel)]="mostrarAdvQuadro"> adversário
          </label>
        </div>
        <p class="muted mini">
          {{ partidasQuadro(l).length }} partida(s) · filtro {{ l.filtro }} · itens desativados (🚫) são ignorados.
        </p>

        <div class="qwrap" *ngIf="partidasQuadro(l).length; else semQuadro">
          <table class="quadro">
            <thead>
              <tr>
                <th class="it">Item</th>
                <th class="c" *ngFor="let p of partidasQuadro(l)"
                    [title]="p.data + ' · vs ' + p.adversario + ' (' + (p.emCasa ? 'casa' : 'fora') + ')'">
                  {{ dataCurta(p) }}
                  <small class="hAdv">{{ p.emCasa ? 'C' : 'F' }}</small>
                </th>
                <th class="c tot">T</th>
                <th class="c med">Média</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let campo of camposQuadro">
                <td class="it">{{ rotuloCampo(campo) }}</td>
                <td class="c cel" *ngFor="let p of partidasQuadro(l)"
                    [class.off]="estaDesativado(l, p.partidaId, campo)">
                  <span class="vc" [class.win]="valPartida(p, campo) > valAdvPartida(p, campo)">
                    {{ valPartida(p, campo) | number:'1.0-2' }}
                  </span>
                  <span class="va" *ngIf="mostrarAdvQuadro">{{ valAdvPartida(p, campo) | number:'1.0-2' }}</span>
                </td>
                <td class="c tot">{{ totalQuadro(l, campo) | number:'1.0-2' }}</td>
                <td class="c med">
                  <b>{{ mediaQuadro(l, campo) | number:'1.2-2' }}</b>
                  <span class="va" *ngIf="mostrarAdvQuadro">{{ mediaQuadroAdv(l, campo) | number:'1.2-2' }}</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <ng-template #semQuadro>
          <p class="muted mini">Sem partidas no filtro atual.</p>
        </ng-template>
      </div>

      <div class="mediaBox">
        <div class="mediaHd">
          <h3>Média aritmética</h3>
          <span class="spacer"></span>
          <span class="pill">{{ l.filtro }}</span>
          <label class="lim">N
            <select [ngModel]="l.limiteMedia" (ngModelChange)="setLimiteMedia(l, $event)">
              <option *ngFor="let n of rangeN(l.detalhe?.partidas?.length || 0)" [value]="n">{{ n }}</option>
            </select>
          </label>
        </div>
        <p class="muted mini">
          Base: {{ l.detalhe?.partidas?.length || 0 }} partida(s) ({{ l.filtro }}), usando as {{ l.limiteMedia }} primeiras.
        </p>
        <div *ngFor="let cat of categorias">
          <h4>{{ cat }}</h4>
          <div class="stat" *ngFor="let m of camposPorCategoria(cat)">
            <span class="rot">{{ m.rotulo }}</span>
            <span class="val">{{ media(l, m.campo) | number:'1.2-2' }}</span>
          </div>
        </div>
      </div>
    </div>
  </ng-template>
  `,
  styles: [`
    .head { display: flex; align-items: center; gap: 14px; }
    .posbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 16px; margin-top: 12px; }
    .posItem { display: flex; align-items: center; gap: 8px; }
    .posItem.right { justify-content: flex-end; }
    .posN { font-size: 22px; font-weight: 800; }
    .posNome { font-weight: 700; }
    .posMid { text-align: center; flex: 1; }
    .lim { color: var(--text-dim); display: flex; align-items: center; gap: 6px; }
    .split { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-top: 16px; }
    .sec { padding: 16px; }
    h2.cCasa { color: var(--accent); }
    h2.cFora { color: var(--accent-2); }
    .cCasa { color: var(--accent); }
    .cFora { color: var(--accent-2); }
    h4 { font-size: 12px; margin: 10px 0 5px; color: var(--text-dim); text-transform: uppercase; letter-spacing: .4px; }
    .acc { border: 1px solid var(--border); border-radius: 8px; margin-bottom: 8px; overflow: hidden; }
    .acc-hd { display: flex; align-items: center; gap: 8px; padding: 9px 11px; cursor: pointer; background: var(--surface-2); }
    .acc-hd:hover { filter: brightness(1.1); }
    .chev { width: 12px; }
    .dt { font-weight: 600; }
    .adv { color: var(--text-dim); }
    .plc { font-weight: 700; }
    .acc-bd { padding: 10px 12px; }
    .mini { font-size: 11px; margin: 0 0 8px; }
    .cabecPar { display: grid; grid-template-columns: 1fr auto 1fr; margin: 4px 0 10px; align-items: center; }
    .cabecPar .lado { font-weight: 800; }
    .cabecPar .lado.clube { color: var(--accent); }
    .cabecPar .lado.adv { color: var(--accent-2); text-align: right; }
    .ln { display: flex; align-items: center; gap: 8px; padding: 3px 0; border-bottom: 1px dashed var(--border); }
    .ln.off { opacity: .38; }
    .ln.off .vClube, .ln.off .vAdv { text-decoration: line-through; }
    .eye { padding: 0 4px; background: transparent; font-size: 14px; }
    .rot { color: var(--text-dim); }
    .val { font-weight: 700; }
    .vClube { font-weight: 700; min-width: 46px; text-align: right; }
    .vAdv { font-weight: 700; min-width: 46px; text-align: left; color: var(--text-dim); }
    .vClube.win { color: var(--win); }
    .vAdv.win { color: var(--win); }
    .quadroBox { margin-top: 16px; border-top: 2px solid var(--border); padding-top: 12px; }
    .chk { display: inline-flex; align-items: center; gap: 5px; font-size: 11px; color: var(--text-dim); cursor: pointer; }
    .qwrap { overflow-x: auto; }
    table.quadro { width: 100%; border-collapse: collapse; font-size: 11px; }
    .quadro th, .quadro td { padding: 4px 5px; border-bottom: 1px solid var(--border); white-space: nowrap; }
    .quadro th { color: var(--text-dim); font-size: 10px; font-weight: 700; }
    .quadro .c { text-align: center; }
    .quadro .it { text-align: left; color: var(--text-dim); min-width: 132px; position: sticky; left: 0;
                  background: var(--surface); z-index: 1; }
    .quadro thead .it { z-index: 2; }
    .quadro .hAdv { display: block; font-size: 8px; opacity: .6; font-weight: 400; }
    .quadro .cel.off { opacity: .3; text-decoration: line-through; }
    .quadro .vc { font-weight: 700; }
    .quadro .vc.win { color: var(--win); }
    .quadro .va { display: block; font-size: 9px; color: var(--text-dim); }
    .quadro .tot { background: rgba(124,92,255,.07); font-weight: 700; }
    .quadro .med { background: rgba(62,166,255,.09); }
    .quadro .med b { font-size: 12px; }
    .quadro tbody tr:hover td { background: var(--surface-2); }
    .quadro tbody tr:hover .it { background: var(--surface-2); }
    .mediaBox { margin-top: 16px; border-top: 2px solid var(--border); padding-top: 12px; }
    .mediaHd { display: flex; align-items: center; }
    .stat { display: flex; justify-content: space-between; padding: 4px 0; border-bottom: 1px dashed var(--border); }

    /* ---------- Análise ---------- */
    .analise { margin-top: 18px; }
    .anHd { display: flex; align-items: center; gap: 10px; }
    .disc { margin-top: 10px; }
    .expGrid { display: grid; grid-template-columns: 1fr auto 1fr; gap: 12px; align-items: center; margin: 12px 0 6px; }
    .expCell { display: flex; flex-direction: column; gap: 2px; padding: 12px; background: var(--surface-2); border-radius: 10px; text-align: center; }
    .expCell b { font-size: 24px; }
    .expCell.mid b.placar { font-size: 30px; color: var(--text); }
    .probBars { display: flex; flex-direction: column; gap: 8px; margin: 6px 0 4px; }
    .pb { display: grid; grid-template-columns: 160px 1fr 48px; align-items: center; gap: 10px; }
    .pbLbl { font-weight: 700; font-size: 12px; }
    .pbTrack { background: var(--surface-2); border-radius: 999px; height: 14px; overflow: hidden; }
    .pbFill { height: 100%; border-radius: 999px; }
    .pbFill.casa { background: var(--accent); }
    .pbFill.fora { background: var(--accent-2); }
    .pbFill.draw { background: var(--draw); }
    .pbPct { font-weight: 800; text-align: right; }
    .miniGrid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin: 14px 0; }
    .miniCard { background: var(--surface-2); border-radius: 10px; padding: 12px; }
    .miniCard h4 { margin-top: 0; }
    .btts { display: flex; gap: 8px; }
    .dc { display: flex; flex-direction: column; gap: 5px; }
    .tag { font-size: 12px; padding: 4px 8px; border-radius: 6px; background: var(--surface); }
    .tag.sim { background: rgba(46,204,113,.2); color: var(--win); font-weight: 700; }
    .tag.nao { background: rgba(231,76,60,.2); color: var(--loss); font-weight: 700; }
    .secHd { display: flex; align-items: center; gap: 8px; }
    .modeloBox { margin: 14px 0; padding: 14px; background: var(--surface-2); border-radius: 10px; }
    .radios { display: flex; flex-wrap: wrap; gap: 8px; margin: 8px 0 4px; }
    .radio { display: inline-flex; align-items: center; gap: 6px; padding: 7px 12px; border-radius: 8px;
             border: 1px solid var(--border); background: var(--surface); cursor: pointer; font-size: 12px; }
    .radio.on { border-color: var(--accent); background: rgba(62,166,255,.12); }
    .radio.on .rNome { color: var(--accent); font-weight: 800; }
    .desc { margin: 6px 0 10px; }
    .params { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 14px; padding: 10px;
              background: var(--surface); border-radius: 8px; margin-bottom: 10px; }
    .pRow { display: flex; flex-direction: column; gap: 3px; font-size: 11px; }
    .pRow.dim { opacity: .4; }
    .pRow input { width: 92px; }
    .modTab { width: 100%; border-collapse: collapse; margin-top: 6px; }
    .modTab th { font-size: 10px; padding: 4px; }
    .modTab td { padding: 6px 4px; border-bottom: 1px dashed var(--border); font-size: 12px; cursor: pointer; }
    .modTab .c { text-align: center; }
    .modTab .mNome { font-weight: 600; }
    .modTab tr.ativo td { background: rgba(62,166,255,.12); }
    .modTab tr.ativo .mNome { color: var(--accent); font-weight: 800; }
    .modTab tr:hover td { background: var(--surface); }
    .fbtns { display: inline-flex; gap: 3px; }
    .ladoHd { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
    .ladoHd h2 { margin: 0; }
    .fbtns.lado { padding: 3px; border: 1px solid var(--border); border-radius: 8px; }

    /* selo do recorte em vigor nos quadros que combinam os dois clubes */
    .recorte { display: inline-block; margin-left: 8px; padding: 2px 8px;
               font-size: 10px; font-weight: 600; letter-spacing: .3px;
               border-radius: 999px; background: var(--surface-2); color: var(--text-dim);
               vertical-align: middle; }

    /* classificação */
    .clasTab { width: 100%; border-collapse: collapse; font-size: 13px; }
    .clasTab th { color: var(--text-dim); font-size: 10px; text-transform: uppercase;
                  letter-spacing: .4px; padding: 6px 8px; text-align: left;
                  border-bottom: 1px solid var(--border); }
    .clasTab td { padding: 6px 8px; border-bottom: 1px solid var(--border); }
    .clasTab .c { text-align: center; }
    .clasTab .pos { color: var(--text-dim); font-weight: 800; width: 34px; }
    .clasTab .pts { background: rgba(62,166,255,.06); }
    .clasTab tr:hover td { background: var(--surface-2); }
    .clasTab tr.tCasa td { background: rgba(62,166,255,.14); }
    .clasTab tr.tCasa .qn { color: var(--accent); font-weight: 700; }
    .clasTab tr.tFora td { background: rgba(124,92,255,.14); }
    .clasTab tr.tFora .qn { color: var(--accent-2); font-weight: 700; }
    .clasTab .sgPos { color: var(--win); }
    .clasTab .sgNeg { color: var(--loss); }
    .aviso { color: var(--draw); }
    .fbtns.lado.bCasa { border-color: var(--accent); }
    .fbtns.lado.bFora { border-color: var(--accent-2); }    .fbtn { padding: 3px 9px; font-size: 11px; border-radius: 6px; background: var(--surface-2); color: var(--text-dim); }
    .fbtn.on { background: var(--accent); color: #06121f; }
    .expBtn { padding: 4px 10px; font-size: 11px; }
    .dadosBox { margin: 18px 0; padding: 14px; background: var(--surface-2); border-radius: 10px; }
    .qGrid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-top: 10px; }
    .qCard { background: var(--surface); border: 1px solid var(--border); border-radius: 9px; padding: 10px 12px; }
    .qCard h4 { margin: 0 0 6px; color: var(--text); }
    .qTab { width: 100%; border-collapse: collapse; }
    .qTab th { font-size: 10px; padding: 3px 4px; }
    .qTab td { padding: 4px; border-bottom: 1px dashed var(--border); font-size: 12px; }
    .qTab .c { text-align: center; }
    .qTab .pos { color: var(--text-dim); font-weight: 700; width: 26px; }
    .qTab .qn { overflow: hidden; text-overflow: ellipsis; max-width: 120px; white-space: nowrap; }
    .qTab .t { font-weight: 800; }
    .qTab .m { color: var(--text-dim); }
    .qTab tr.destaque td { background: rgba(62,166,255,.12); }
    .qTab tr.destaque .qn { font-weight: 800; color: var(--accent); }
    @media (max-width: 1100px) { .qGrid { grid-template-columns: repeat(2, 1fr); } }
    .prevTab { width: 100%; }
    .prevTab th { font-size: 11px; }
    .prevTab td { padding: 7px 10px; }
    .prevTab .cons { color: var(--text-dim); }
    .prevTab .mod { color: var(--text); font-weight: 700; }
    .prevTab .arr { color: var(--accent); font-weight: 700; }
    @media (max-width: 900px) {
      .split { grid-template-columns: 1fr; }
      .miniGrid { grid-template-columns: 1fr; }
      .qGrid { grid-template-columns: 1fr; }
      .pb { grid-template-columns: 120px 1fr 42px; }
    }
  `]
})
export class ComparacaoComponent implements OnInit {
  casa: LadoClube = this.novoLado();
  fora: LadoClube = this.novoLado();
  limiteBusca = 5;
  filtros = ['TODOS', 'CASA', 'FORA'];
  /** N máximo de jogos no campeonato (default do seletor de "últimas N"). */
  maxJogosCampeonato = 0;
  modelos = MODELOS;
  modeloSel: ModeloId = 'dixoncoles';
  mostrarParams = false;
  linhasOver = ['0.5', '1.5', '2.5', '3.5', '4.5'];
  /** Itens do quadro comparativo por partida (ordem definida pelo usuário). */
  camposQuadro = [
    'escanteios', 'finalizacoes', 'finalizacoesNoGol', 'finalizacoesParaFora',
    'finalizacoesDentroArea', 'finalizacoesForaArea', 'defesasDoGoleiro',
    'faltas', 'desarmes', 'cartoesAmarelos', 'impedimentos', 'laterais', 'tirosDeMeta'
  ];
  mostrarAdvQuadro = true;
  paramRho = PARAMS_PADRAO.rho;
  paramL3 = PARAMS_PADRAO.lambda3;
  paramR = PARAMS_PADRAO.r;
  ranking?: Ranking;
  rankingExpandido = false;
  categorias = CATEGORIAS;
  campos: StatMeta[] = STAT_FIELDS;
  private debounce?: any;

  // ---------------- Classificação ----------------
  /** Recorte da classificação; independente dos painéis e do ranking. */
  filtroTabela = 'TODOS';
  limiteTabela = 5;
  tabela: LinhaTabela[] = [];
  carregandoTabela = false;
  campeonatoNome = '';
  private clubesDoCampeonato: { id: number; nome: string }[] = [];
  private debounceTabela?: any;

  /** Recorte do painel "Dados dos clubes"; também independente. */
  filtroRanking = 'TODOS';

  constructor(private route: ActivatedRoute, private router: Router,
              private partidaSvc: PartidaService, private clubeSvc: ClubeService) {}

  novoLado(): LadoClube {
    return { clubeId: 0, nome: '', filtro: 'TODOS',
             limiteMedia: 5, abertos: new Set(), desativados: new Map() };
  }

  ngOnInit() {
    const qp = this.route.snapshot.queryParamMap;
    this.casa.clubeId = Number(qp.get('a'));
    this.fora.clubeId = Number(qp.get('b'));
    const limiteQp = Number(qp.get('limite'));
    // Descobre o N máximo do campeonato; se a URL não trouxe limite, adota o máximo.
    this.descobrirMaxJogos(() => {
      this.limiteBusca = limiteQp || this.maxJogosCampeonato || 5;
      this.casa.limiteMedia = this.limiteBusca;
      this.fora.limiteMedia = this.limiteBusca;
      this.carregar();
    });
  }

  /**
   * Consulta o ranking do campeonato (limite alto) só para descobrir o clube
   * com mais jogos. Esse número vira o default de "buscar últimas N partidas",
   * aqui e no dashboard.
   */
  private descobrirMaxJogos(done: () => void) {
    this.partidaSvc.ranking('TODOS', 9999, this.casa.clubeId).subscribe({
      next: r => {
        let max = 0;
        for (const q of r.quesitos) for (const it of q.itens) if (it.jogos > max) max = it.jogos;
        this.maxJogosCampeonato = max;
        done();
      },
      error: () => { this.maxJogosCampeonato = 0; done(); }
    });
  }

  carregar() {
    [this.casa, this.fora].forEach(l => this.carregarLado(l));
    this.carregarRanking();
    this.carregarTabela();
  }

  /** Carrega as partidas de UM lado, com o filtro daquele lado. */
  carregarLado(l: LadoClube) {
    this.partidaSvc.detalheClube(l.clubeId, l.filtro, this.limiteBusca).subscribe(d => {
      l.nome = d.clubeNome;
      l.detalhe = d;
      if (l.limiteMedia > d.partidas.length) l.limiteMedia = d.partidas.length || 1;
    });
  }

  /**
   * Troca o filtro de UM lado e recarrega só ele.
   *
   * Não chama carregar(): o outro painel não pode ser tocado, e o ranking não
   * depende de filtro. Recarregar tudo aqui reintroduziria o acoplamento que
   * esta tela precisa não ter.
   */
  setFiltro(l: LadoClube, f: string) {
    if (l.filtro === f) return;
    l.filtro = f;
    this.carregarLado(l);
  }

  // ---------------- Ranking / Dados dos clubes ----------------
  carregarRanking() {
    // clubeId restringe o ranking ao campeonato do clube comparado.
    this.partidaSvc.ranking(this.filtroRanking, this.limiteBusca, this.casa.clubeId)
      .subscribe(r => this.ranking = r);
  }

  /**
   * Troca o recorte do ranking.
   *
   * Ele é independente dos painéis DE PROPÓSITO: são perguntas diferentes.
   * O painel pergunta "como este clube joga em casa"; o ranking pergunta "como
   * ele se compara aos outros". Amarrar os dois obrigaria a mudar a base de
   * comparação toda vez que se olha um lado.
   */
  setFiltroRanking(f: string) {
    if (this.filtroRanking === f) return;
    this.filtroRanking = f;
    this.carregarRanking();
  }

  // ---------------- Classificação do campeonato ----------------

  setFiltroTabela(f: string) {
    if (this.filtroTabela === f) return;
    this.filtroTabela = f;
    this.carregarTabela();
  }

  onLimiteTabela(v: number) {
    this.limiteTabela = Math.max(1, Number(v) || 1);
    clearTimeout(this.debounceTabela);
    this.debounceTabela = setTimeout(() => this.carregarTabela(), 400);
  }

  /**
   * Monta a classificação no cliente, um detalhe por clube.
   *
   * São ~20 requisições — o mesmo que o dashboard já faz. Vale o custo porque é
   * o que permite a tabela obedecer ao MESMO recorte da tela (últimos N +
   * CASA/FORA); um endpoint pronto de classificação devolveria sempre a
   * temporada inteira.
   *
   * A lista de clubes é buscada uma vez e reaproveitada entre trocas de filtro.
   */
  carregarTabela() {
    if (!this.casa.clubeId) return;
    if (this.clubesDoCampeonato.length) { this.montarTabela(); return; }

    this.clubeSvc.listar().subscribe(todos => {
      const eu = todos.find(c => c.id === this.casa.clubeId);
      const campId = eu?.campeonato?.id;
      this.campeonatoNome = eu?.campeonato?.nome || '';
      this.clubesDoCampeonato = todos
        .filter(c => c.id != null && c.campeonato?.id === campId)
        .map(c => ({ id: c.id!, nome: c.nome }));
      this.montarTabela();
    });
  }

  private montarTabela() {
    if (!this.clubesDoCampeonato.length) { this.tabela = []; return; }
    this.carregandoTabela = true;

    const linhas: LinhaTabela[] = [];
    let pendentes = this.clubesDoCampeonato.length;

    const encerrar = () => {
      if (--pendentes > 0) return;
      // Clube sem jogo no recorte sai da tabela: uma linha zerada em CASA/FORA
      // sugeriria "último colocado" quando na verdade é ausência de dado.
      this.tabela = linhas.filter(l => l.jogos > 0).sort((a, b) =>
        b.pontos - a.pontos || b.v - a.v || b.sg - a.sg || b.gf - a.gf ||
        a.nome.localeCompare(b.nome, 'pt', { sensitivity: 'base' }));
      this.carregandoTabela = false;
    };

    for (const c of this.clubesDoCampeonato) {
      this.partidaSvc.detalheClube(c.id, this.filtroTabela, this.limiteTabela).subscribe({
        next: d => {
          const ps = d.partidas.slice(0, this.limiteTabela);
          const v = ps.filter(p => p.resultado === 'V').length;
          const e = ps.filter(p => p.resultado === 'E').length;
          const gf = ps.reduce((s2, p) => s2 + p.golsFeitos, 0);
          const gs = ps.reduce((s2, p) => s2 + p.golsSofridos, 0);
          linhas.push({
            clubeId: c.id, nome: c.nome, jogos: ps.length,
            pontos: v * 3 + e, v, e, d: ps.length - v - e,
            gf, gs, sg: gf - gs
          });
          encerrar();
        },
        error: () => encerrar()
      });
    }
  }

  /** Texto do selo: qual recorte alimenta os quadros combinados. */
  recorte(): string {
    return `${this.casa.nome || 'casa'} ${this.casa.filtro} × ${this.fora.nome || 'fora'} ${this.fora.filtro}`;
  }

  /** Só os 2 clubes comparados, preservando a posição no ranking global. */
  itensComparados(q: RankingQuesito) {
    return q.itens
      .map((it, i) => ({ ...it, pos: i + 1 }))
      .filter(it => it.clubeId === this.casa.clubeId || it.clubeId === this.fora.clubeId);
  }
  topN(q: RankingQuesito, n: number) {
    return q.itens.slice(0, n).map((it, i) => ({ ...it, pos: i + 1 }));
  }
  ehComparado(clubeId: number): boolean {
    return clubeId === this.casa.clubeId || clubeId === this.fora.clubeId;
  }

  /** Quesito de classificação por pontos (chave 'posicaoTabela'), se presente. */
  private quesitoPosicao(): RankingQuesito | undefined {
    return this.ranking?.quesitos.find(q => q.chave === 'posicaoTabela');
  }
  /** Total de clubes no ranking (denominador da posição). */
  totalClubesRanking(): number {
    return this.quesitoPosicao()?.itens.length ?? 0;
  }
  /** Posição (1-based) do clube na tabela de pontos; 0 se não encontrado. */
  posicaoTabela(clubeId: number): number {
    const q = this.quesitoPosicao();
    if (!q) return 0;
    const idx = q.itens.findIndex(it => it.clubeId === clubeId);
    return idx < 0 ? 0 : idx + 1;
  }
  /** Texto "3º" ou "—" para o cabeçalho. */
  posicaoTexto(clubeId: number): string {
    const p = this.posicaoTabela(clubeId);
    return p > 0 ? `${p}º` : '—';
  }

  onLimiteBusca(v: number) {
    this.limiteBusca = v;
    clearTimeout(this.debounce);
    this.debounce = setTimeout(() => this.carregar(), 400);
  }

  camposPorCategoria(cat: string): StatMeta[] { return this.campos.filter(c => c.categoria === cat); }
  rangeN(max: number): number[] { return Array.from({ length: Math.max(1, max) }, (_, i) => i + 1); }

  toggleAccordion(l: LadoClube, id: number) {
    if (l.abertos.has(id)) l.abertos.delete(id); else l.abertos.add(id);
  }
  setLimiteMedia(l: LadoClube, v: number) { l.limiteMedia = Number(v); }

  estaDesativado(l: LadoClube, partidaId: number, campo: string): boolean {
    return l.desativados.get(partidaId)?.has(campo) ?? false;
  }
  toggleCampo(l: LadoClube, partidaId: number, campo: string) {
    let set = l.desativados.get(partidaId);
    if (!set) { set = new Set(); l.desativados.set(partidaId, set); }
    if (set.has(campo)) set.delete(campo); else set.add(campo);
  }

  valPartida(p: PartidaResumo, campo: string): number { return p.estatisticas?.[campo] ?? 0; }
  valAdvPartida(p: PartidaResumo, campo: string): number { return p.estatisticasAdversario?.[campo] ?? 0; }

  /**
   * Média aritmética do campo considerando as N primeiras partidas (limiteMedia),
   * ignorando as partidas em que o item foi desativado (👁 → 🚫).
   */
  media(l: LadoClube, campo: string): number {
    const fonte = l.detalhe;
    if (!fonte) return 0;
    const partidas = fonte.partidas.slice(0, l.limiteMedia);
    let soma = 0, n = 0;
    for (const p of partidas) {
      if (this.estaDesativado(l, p.partidaId, campo)) continue;
      soma += this.valPartida(p, campo);
      n++;
    }
    return n === 0 ? 0 : soma / n;
  }

  // =====================================================================
  //  MODELO DE ANÁLISE / PROBABILIDADES
  //  Todas as estimativas partem das médias de cada lado (respeitando o N
  //  e os itens desativados de cada painel). É um modelo simples e
  //  determinístico — referência, não garantia.
  // =====================================================================

  /** Gols esperados (xG) ofensivos de cada lado, temperados por finalizações/grandes chances. */
  xgCasa(): number { return this.forcaOfensiva(this.casa, this.fora); }
  xgFora(): number { return this.forcaOfensiva(this.fora, this.casa); }

  /**
   * Combina xG próprio (ataque) com a fragilidade defensiva do adversário
   * (gols evitados baixos, poucas defesas). Mantém tudo derivado das médias.
   */
  private forcaOfensiva(ataque: LadoClube, defesa: LadoClube): number {
    const xg = this.media(ataque, 'golsEsperados');
    const gc = this.media(ataque, 'grandesChances');
    const finGol = this.media(ataque, 'finalizacoesNoGol');
    // base: mistura de xG com sinais de volume ofensivo
    let base = xg > 0 ? xg : 0;
    base = 0.70 * base + 0.20 * (gc * 0.45) + 0.10 * (finGol * 0.18);
    // ajuste defensivo do adversário: defesas do goleiro altas reduzem levemente
    const defAdv = this.media(defesa, 'defesasDoGoleiro');
    const fator = 1 / (1 + 0.06 * Math.max(0, defAdv));
    const val = base * fator;
    return isFinite(val) && val > 0 ? val : Math.max(0.05, xg);
  }

  /** Resultado do modelo probabilístico selecionado. */
  res(): ResultadoModelo {
    return calcular(this.modeloSel, this.xgCasa(), this.xgFora(), {
      rho: this.paramRho, lambda3: this.paramL3, r: this.paramR
    });
  }

  /** Compat: mercados agregados vindos do modelo escolhido. */
  probs() { return this.res(); }

  /** Placar mais provável segundo o modelo escolhido. */
  placarProvavel(): { casa: number; fora: number } {
    const r = this.res();
    return { casa: r.placarCasa, fora: r.placarFora };
  }

  setModelo(id: ModeloId) { this.modeloSel = id; }
  descricaoModeloNome(): string {
    return this.modelos.find(m => m.id === this.modeloSel)?.nome ?? '';
  }
  descricaoModelo(): string {
    return this.modelos.find(m => m.id === this.modeloSel)?.descricao ?? '';
  }
  /** Comparativo lado a lado de todos os modelos (transparência). */
  todosModelos() {
    return this.modelos.map(m => ({
      info: m,
      r: calcular(m.id, this.xgCasa(), this.xgFora(),
                  { rho: this.paramRho, lambda3: this.paramL3, r: this.paramR })
    }));
  }
  resetParams() {
    this.paramRho = PARAMS_PADRAO.rho;
    this.paramL3 = PARAMS_PADRAO.lambda3;
    this.paramR = PARAMS_PADRAO.r;
  }

  /**
   * Previsões de totais. Para cada mercado somamos a média dos dois lados
   * (o total esperado da partida) e aplicamos fatores:
   *   conservadora = piso (floor de um desconto),
   *   moderada     = valor central arredondado,
   *   arrojada     = um nível acima da moderada.
   */
  previsoes(): LinhaPrev[] {
    const somaMedias = (campo: string) => this.media(this.casa, campo) + this.media(this.fora, campo);

    // total de gols vem do modelo (xG somado), não das médias de gols feitos
    const golsTotal = this.xgCasa() + this.xgFora();

    const linhas: LinhaPrev[] = [];

    // 1..3 já saem no quadro de probabilidades acima; aqui vão os totais 4..12.
    linhas.push(this.linhaTotal('Total de gols', golsTotal, 0.85, 1.0, 1.18));
    linhas.push(this.linhaTotal('Total de escanteios', somaMedias('escanteios'), 0.82, 1.0, 1.15));
    linhas.push(this.linhaTotal('Total de chutes no gol', somaMedias('finalizacoesNoGol'), 0.82, 1.0, 1.15));
    linhas.push(this.linhaTotal('Total de chutes (finalizações)', somaMedias('finalizacoes'), 0.85, 1.0, 1.15));
    linhas.push(this.linhaTotal('Total de impedimentos', somaMedias('impedimentos'), 0.70, 1.0, 1.25));
    linhas.push(this.linhaTotal('Total de desarmes', somaMedias('desarmes'), 0.85, 1.0, 1.12));
    linhas.push(this.linhaTotal('Total de laterais', somaMedias('laterais'), 0.85, 1.0, 1.12));
    linhas.push(this.linhaTotal('Total de tiros de meta', somaMedias('tirosDeMeta'), 0.80, 1.0, 1.20));
    linhas.push(this.linhaTotal('Total de cartões amarelos', somaMedias('cartoesAmarelos'), 0.75, 1.0, 1.25));

    return linhas;
  }

  /** Formata uma linha "+X" (limite inferior) para cada visão. */
  private linhaTotal(rotulo: string, base: number, fc: number, fm: number, fa: number): LinhaPrev {
    const cons = Math.max(0, Math.floor(base * fc));
    const mod = Math.max(0, Math.round(base * fm));
    const arr = Math.max(0, Math.ceil(base * fa));
    return {
      rotulo,
      conservadora: `+${cons}`,
      moderada: `+${mod}`,
      arrojada: `+${arr}`
    };
  }

  // ---------------- Quadro comparativo por partida ----------------

  /** Partidas que alimentam o quadro (mesma base do quadro de média). */
  partidasQuadro(l: LadoClube): PartidaResumo[] {
    const fonte = l.detalhe;
    if (!fonte) return [];
    return fonte.partidas.slice(0, l.limiteMedia);
  }

  /** Metadata (rótulo) de um campo do quadro. */
  metaCampo(campo: string): StatMeta | undefined {
    return this.campos.find(c => c.campo === campo);
  }
  rotuloCampo(campo: string): string {
    return this.metaCampo(campo)?.rotulo ?? campo;
  }

  /**
   * Média do campo no quadro: usa as mesmas partidas exibidas e respeita
   * os itens desativados (👁 → 🚫), igual ao quadro de média aritmética.
   */
  mediaQuadro(l: LadoClube, campo: string): number {
    const ps = this.partidasQuadro(l);
    let soma = 0, n = 0;
    for (const p of ps) {
      if (this.estaDesativado(l, p.partidaId, campo)) continue;
      soma += this.valPartida(p, campo);
      n++;
    }
    return n === 0 ? 0 : soma / n;
  }

  /** Média do adversário no mesmo recorte (contexto). */
  mediaQuadroAdv(l: LadoClube, campo: string): number {
    const ps = this.partidasQuadro(l);
    let soma = 0, n = 0;
    for (const p of ps) {
      if (this.estaDesativado(l, p.partidaId, campo)) continue;
      soma += this.valAdvPartida(p, campo);
      n++;
    }
    return n === 0 ? 0 : soma / n;
  }

  /** Total somado do campo (linha do quadro). */
  totalQuadro(l: LadoClube, campo: string): number {
    const ps = this.partidasQuadro(l);
    let soma = 0;
    for (const p of ps) {
      if (this.estaDesativado(l, p.partidaId, campo)) continue;
      soma += this.valPartida(p, campo);
    }
    return soma;
  }

  /** Data curta (dd/MM) para caber no cabeçalho das colunas. */
  dataCurta(p: PartidaResumo): string {
    return (p.data || '').slice(0, 5);
  }

  voltar() { this.router.navigate(['/dashboard']); }
}