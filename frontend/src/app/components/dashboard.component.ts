import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Nacao, Campeonato, Clube, ClubeDetalhe } from '../models/entities.model';
import { NacaoService } from '../services/nacao.service';
import { CampeonatoService } from '../services/campeonato.service';
import { ClubeService } from '../services/clube.service';
import { PartidaService } from '../services/partida.service';

interface LinhaTabela {
  clube: Clube;
  jogos: number;
  pontos: number;
  v: number; e: number; d: number;
  gf: number; gs: number; sg: number;
  amarelos: number;
  seq: string[];          // V/E/D recentes (mais antiga → recente)
  carregando: boolean;
  msg?: string;
}

interface GrupoCampeonato {
  nacao: Nacao;
  campeonato: Campeonato;
  linhas: LinhaTabela[];
  aberto: boolean;
  limite: number;
  debounce?: any;
}

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
  <div class="head">
    <div>
      <h1>Dashboard</h1>
      <p class="muted">Classificação por campeonato — clique no cabeçalho para expandir.</p>
    </div>
  </div>

  <div *ngIf="selecionados.length === 2" class="cmpbar">
    <span>Comparar <b>{{ selecionados[0].nome }}</b> × <b>{{ selecionados[1].nome }}</b></span>
    <button class="btn-primary pulse" (click)="comparar()">⚖️ Comparar</button>
    <button class="btn-ghost" (click)="limparSelecao()">Limpar</button>
  </div>

  <div *ngIf="!grupos.length" class="card vazio">
    Nenhum campeonato com clubes. Vá em <a (click)="irCadastros()">Cadastros</a>.
  </div>

  <!-- Um accordion por campeonato -->
  <div *ngFor="let g of grupos" class="camp card">
    <div class="camp-hd" (click)="g.aberto = !g.aberto">
      <span class="chev">{{ g.aberto ? '▾' : '▸' }}</span>
      <b class="camp-nome">{{ g.nacao.nome }} · {{ g.campeonato.nome }}</b>
      <span class="pill">{{ g.linhas.length }} clube(s)</span>
      <span class="spacer"></span>
      <label class="lim" (click)="$event.stopPropagation()" title="Últimas N partidas deste campeonato">
        Últimas
        <input type="number" min="1" [ngModel]="g.limite"
               (ngModelChange)="onLimiteGrupo(g, $event)" style="width:56px">
      </label>
      <span class="mini muted">líder: {{ g.linhas[0]?.clube?.nome || '—' }}</span>
    </div>

    <div class="camp-bd" *ngIf="g.aberto">
      <div class="tabwrap">
        <table class="classificacao">
          <thead>
            <tr>
              <th class="pos">#</th>
              <th>Clube</th>
              <th class="c" title="Jogos">J</th>
              <th class="c pts" title="Pontos">P</th>
              <th class="c" title="Vitórias">V</th>
              <th class="c" title="Empates">E</th>
              <th class="c" title="Derrotas">D</th>
              <th class="c" title="Gols feitos">GF</th>
              <th class="c" title="Gols sofridos">GS</th>
              <th class="c" title="Saldo de gols">SG</th>
              <th class="c" title="Cartões amarelos">CA</th>
              <th class="c seqcol">Últimas</th>
              <th class="c acol">Ações</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let ln of g.linhas; let i = index" [class.sel]="estaSelecionado(ln.clube)">
              <td class="pos">{{ i + 1 }}</td>
              <td class="nome">
                <span class="cnome" (click)="abrirClube(ln.clube)">{{ ln.clube.nome }}</span>
                <span *ngIf="ln.carregando" class="mini muted"> · importando…</span>
                <span *ngIf="ln.msg" class="mini msg"> · {{ ln.msg }}</span>
              </td>
              <td class="c">{{ ln.jogos }}</td>
              <td class="c pts"><b>{{ ln.pontos }}</b></td>
              <td class="c">{{ ln.v }}</td>
              <td class="c">{{ ln.e }}</td>
              <td class="c">{{ ln.d }}</td>
              <td class="c">{{ ln.gf }}</td>
              <td class="c">{{ ln.gs }}</td>
              <td class="c" [class.pos-sg]="ln.sg > 0" [class.neg-sg]="ln.sg < 0">
                {{ ln.sg > 0 ? '+' : '' }}{{ ln.sg }}
              </td>
              <td class="c">{{ ln.amarelos }}</td>
              <td class="c seqcol">
                <span class="seq">
                  <span *ngFor="let r of ln.seq" class="dot"
                        [class.v]="r==='V'" [class.e]="r==='E'" [class.d]="r==='D'">{{ r }}</span>
                  <span *ngIf="!ln.seq.length" class="muted mini">—</span>
                </span>
              </td>
              <td class="c acol">
                <div class="acoes">
                  <input type="checkbox" [checked]="estaSelecionado(ln.clube)"
                         (change)="toggleSelecao(ln.clube)" title="Selecionar para comparar">
                  <label class="upload" title="Importar 1 ou mais arquivos partida_N.html (SofaScore)">🌐
                    <input type="file" accept=".html,.htm" hidden multiple
                           (change)="upload($event, ln, g.campeonato)">
                  </label>
                </div>
              </td>
            </tr>
            <tr *ngIf="!g.linhas.length">
              <td colspan="13" class="muted center">Nenhum clube neste campeonato.</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="mini muted leg">
        Ordenação: pontos → vitórias → saldo de gols → gols feitos → menos cartões amarelos → nome.
        Clique no nome do clube para ver o detalhe; 🌐 importa partida(s).
      </p>
    </div>
  </div>
  `,
  styles: [`
    .head { display: flex; align-items: flex-end; gap: 16px; }
    .lim { color: var(--text-dim); font-size: 13px; display: flex; align-items: center; gap: 6px; }

    .camp { margin-top: 14px; overflow: hidden; }
    .camp-hd { display: flex; align-items: center; gap: 10px; padding: 13px 16px; cursor: pointer; background: var(--surface-2); }
    .camp-hd:hover { filter: brightness(1.1); }
    .chev { width: 12px; color: var(--text-dim); }
    .camp-nome { font-size: 15px; }
    .camp-bd { padding: 6px 6px 12px; }
    .leg { margin: 8px 10px 0; }

    .tabwrap { overflow-x: auto; }
    table.classificacao { width: 100%; border-collapse: collapse; min-width: 760px; }
    .classificacao th, .classificacao td { padding: 8px 8px; border-bottom: 1px solid var(--border); white-space: nowrap; }
    .classificacao th { color: var(--text-dim); font-size: 11px; text-transform: uppercase; letter-spacing: .4px; }
    .classificacao .c { text-align: center; }
    .classificacao .pos { text-align: center; width: 34px; color: var(--text-dim); font-weight: 800; }
    .classificacao .pts { background: rgba(62,166,255,.06); }
    .classificacao td.pts b { font-size: 15px; }
    .classificacao tr.sel td { background: rgba(62,166,255,.12); }
    .classificacao tr:hover td { background: var(--surface-2); }
    .cnome { font-weight: 700; cursor: pointer; }
    .cnome:hover { color: var(--accent); }
    .nome { min-width: 160px; }
    .pos-sg { color: var(--win); font-weight: 700; }
    .neg-sg { color: var(--loss); font-weight: 700; }

    .seqcol { width: 130px; }
    .seq { display: inline-flex; gap: 3px; }
    .dot { width: 18px; height: 18px; border-radius: 4px; display: grid; place-items: center; font-size: 9px; font-weight: 800; background: var(--surface-2); }
    .dot.v { background: var(--win); color: #06210f; }
    .dot.e { background: var(--draw); color: #211c06; }
    .dot.d { background: var(--loss); color: #fff; }

    .acol { width: 74px; }
    .acoes { display: inline-flex; align-items: center; gap: 8px; }
    .upload { cursor: pointer; font-size: 16px; }
    .mini { font-size: 11px; }
    .msg { color: var(--accent); }

    .cmpbar { display: flex; align-items: center; gap: 12px; padding: 12px 16px; margin: 14px 0; background: rgba(124,92,255,.12); border: 1px solid var(--accent-2); border-radius: var(--radius); }
    .vazio { padding: 30px; text-align: center; color: var(--text-dim); }
    .pulse { animation: pulse 1.4s infinite; }
    @keyframes pulse { 0%,100% { box-shadow: 0 0 0 0 rgba(62,166,255,.5); } 50% { box-shadow: 0 0 0 8px rgba(62,166,255,0); } }
  `]
})
export class DashboardComponent implements OnInit {
  grupos: GrupoCampeonato[] = [];
  selecionados: Clube[] = [];
  limiteInicial = 5;

  constructor(
    private nacaoSvc: NacaoService,
    private campSvc: CampeonatoService,
    private clubeSvc: ClubeService,
    private partidaSvc: PartidaService,
    private router: Router
  ) {}

  ngOnInit() { this.carregar(); }

  onLimiteGrupo(g: GrupoCampeonato, v: number) {
    g.limite = v;
    clearTimeout(g.debounce);
    g.debounce = setTimeout(() => {
      g.linhas.forEach(ln => this.atualizarLinha(ln, g));
    }, 400);
  }

  carregar() {
    this.nacaoSvc.listar().subscribe(nacoes => {
      this.grupos = [];
      if (!nacoes.length) return;
      nacoes.forEach(nacao => {
        this.campSvc.listar(nacao.id).subscribe(camps => {
          camps.forEach(camp => {
            this.clubeSvc.listar(camp.id).subscribe(clubes => {
              if (!clubes.length) return;
              const grupo: GrupoCampeonato = { nacao, campeonato: camp, linhas: [], aberto: true, limite: this.limiteInicial };
              clubes.forEach(cl => {
                const ln: LinhaTabela = {
                  clube: cl, jogos: 0, pontos: 0, v: 0, e: 0, d: 0,
                  gf: 0, gs: 0, sg: 0, amarelos: 0, seq: [], carregando: false
                };
                grupo.linhas.push(ln);
                this.atualizarLinha(ln, grupo);
              });
              this.grupos.push(grupo);
              this.ordenarGrupos();
            });
          });
        });
      });
    });
  }

  /** Busca detalhe do clube e agrega os números da tabela de classificação. */
  atualizarLinha(ln: LinhaTabela, grupo: GrupoCampeonato) {
    this.partidaSvc.detalheClube(ln.clube.id!, 'TODOS', grupo.limite).subscribe((d: ClubeDetalhe) => {
      ln.jogos = d.totalPartidas;
      ln.v = d.partidas.filter(p => p.resultado === 'V').length;
      ln.e = d.partidas.filter(p => p.resultado === 'E').length;
      ln.d = d.partidas.filter(p => p.resultado === 'D').length;
      ln.pontos = ln.v * 3 + ln.e;
      ln.gf = d.partidas.reduce((s, p) => s + p.golsFeitos, 0);
      ln.gs = d.partidas.reduce((s, p) => s + p.golsSofridos, 0);
      ln.sg = ln.gf - ln.gs;
      ln.amarelos = d.partidas.reduce((s, p) => s + Math.round(p.estatisticas?.['cartoesAmarelos'] ?? 0), 0);
      ln.seq = d.partidas.map(p => p.resultado).slice(0, grupo.limite).reverse();
      this.ordenar(grupo);
    });
  }

  /** Accordions em ordem alfabética: nação, depois campeonato. */
  ordenarGrupos() {
    this.grupos.sort((a, b) =>
      a.nacao.nome.localeCompare(b.nacao.nome, 'pt', { sensitivity: 'base' }) ||
      a.campeonato.nome.localeCompare(b.campeonato.nome, 'pt', { sensitivity: 'base' }));
  }

  /** Pontos → vitórias → saldo → gols feitos → menos cartões → nome. */
  ordenar(grupo: GrupoCampeonato) {
    grupo.linhas.sort((a, b) =>
      b.pontos - a.pontos ||
      b.v - a.v ||
      b.sg - a.sg ||
      b.gf - a.gf ||
      a.amarelos - b.amarelos ||
      a.clube.nome.localeCompare(b.clube.nome));
  }

  upload(ev: Event, ln: LinhaTabela, camp: Campeonato) {
    const input = ev.target as HTMLInputElement;
    const files = input.files;
    if (!files || !files.length) return;
    const grupo = this.grupos.find(g => g.campeonato.id === camp.id)!;
    ln.carregando = true; ln.msg = undefined;

    if (files.length === 1) {
      this.partidaSvc.importarHtml(files[0], camp.id!, ln.clube.id).subscribe({
        next: r => {
          ln.carregando = false;
          ln.msg = r.importada
            ? `✓ ${r.clubeCasa} ${r.golsCasa}×${r.golsFora} ${r.clubeFora}`
            : `⚠ ${r.mensagem}`;
          this.atualizarLinha(ln, grupo);
          setTimeout(() => ln.msg = undefined, 6000);
        },
        error: () => { ln.carregando = false; ln.msg = '✕ Erro ao importar.'; }
      });
    } else {
      const lista = Array.from(files);
      this.partidaSvc.importarLote(lista, camp.id!, ln.clube.id).subscribe({
        next: r => {
          ln.carregando = false;
          ln.msg = `✓ ${r.importadas} importada(s), ${r.ignoradas} ignorada(s)` + (r.comErro ? `, ${r.comErro} erro(s)` : '');
          this.atualizarLinha(ln, grupo);
          setTimeout(() => ln.msg = undefined, 8000);
        },
        error: () => { ln.carregando = false; ln.msg = '✕ Erro ao importar lote.'; }
      });
    }
    input.value = '';
  }

  // seleção p/ comparação
  estaSelecionado(c: Clube) { return this.selecionados.some(s => s.id === c.id); }
  toggleSelecao(c: Clube) {
    const i = this.selecionados.findIndex(s => s.id === c.id);
    if (i >= 0) this.selecionados.splice(i, 1);
    else { if (this.selecionados.length >= 2) this.selecionados.shift(); this.selecionados.push(c); }
  }
  limparSelecao() { this.selecionados = []; }
  comparar() {
    if (this.selecionados.length !== 2) return;
    const g = this.grupos.find(x => x.linhas.some(l => l.clube.id === this.selecionados[0].id));
    const limite = g ? g.limite : this.limiteInicial;
    this.router.navigate(['/comparacao'], { queryParams: { a: this.selecionados[0].id, b: this.selecionados[1].id, limite } });
  }

  abrirClube(c: Clube) { this.router.navigate(['/clube', c.id]); }
  irCadastros() { this.router.navigate(['/cadastros']); }
}