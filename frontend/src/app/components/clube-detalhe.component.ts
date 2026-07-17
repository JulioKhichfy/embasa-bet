import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ClubeDetalhe, PartidaResumo } from '../models/entities.model';
import { PartidaService } from '../services/partida.service';
import { STAT_FIELDS, CATEGORIAS, StatMeta } from '../models/sofascore.model';

@Component({
  selector: 'app-clube-detalhe',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
  <div class="head" *ngIf="detalhe">
    <button class="btn-ghost" (click)="voltar()">← Voltar</button>
    <h1>{{ detalhe.clubeNome }}</h1>
    <span class="spacer"></span>
    <div class="filtros">
      <button *ngFor="let f of ['TODOS','CASA','FORA']" [class.btn-primary]="filtro===f" (click)="setFiltro(f)">{{ f }}</button>
      <label class="lim">N
        <input type="number" min="1" [ngModel]="limite" (ngModelChange)="onLimite($event)" style="width:60px">
      </label>
    </div>
  </div>

  <div *ngIf="detalhe" class="resumo card">
    <div class="metric"><b>{{ detalhe.mediaPontos | number:'1.2-2' }}</b><small>pontos/jogo</small></div>
    <div class="metric"><b>{{ detalhe.mediaGolsFeitos | number:'1.2-2' }}</b><small>gols feitos</small></div>
    <div class="metric"><b>{{ detalhe.mediaGolsSofridos | number:'1.2-2' }}</b><small>gols sofridos</small></div>
    <div class="metric"><b>{{ detalhe.totalPartidas }}</b><small>partidas ({{ filtro }})</small></div>
  </div>

  <div class="cols" *ngIf="detalhe">
    <div class="card sec">
      <h2>Últimas partidas</h2>
      <p class="muted mini">Clique numa linha para ver as estatísticas da partida abaixo.</p>
      <table>
        <thead><tr><th>Data</th><th>Adversário</th><th class="center">L/C</th><th class="center">Placar</th><th class="center">Res</th></tr></thead>
        <tbody>
          <tr *ngFor="let p of detalhe.partidas" class="linha"
              [class.ativa]="partidaSel?.partidaId === p.partidaId" (click)="selecionarPartida(p)">
            <td>{{ p.data }}</td>
            <td>{{ p.adversario }}</td>
            <td class="center"><span class="pill">{{ p.emCasa ? 'Casa' : 'Fora' }}</span></td>
            <td class="center">{{ p.golsFeitos }} × {{ p.golsSofridos }}</td>
            <td class="center"><span class="badge" [ngClass]="p.resultado.toLowerCase()">{{ p.resultado }}</span></td>
          </tr>
          <tr *ngIf="!detalhe.partidas.length"><td colspan="5" class="muted center">Sem partidas.</td></tr>
        </tbody>
      </table>
    </div>

    <div class="card sec">
      <h2>Média aritmética das estatísticas</h2>
      <div *ngFor="let cat of categorias">
        <h3>{{ cat }}</h3>
        <div class="stat" *ngFor="let m of camposPorCategoria(cat)">
          <span class="rot">{{ m.rotulo }}</span>
          <span class="val">{{ media(m.campo) | number:'1.2-2' }}</span>
        </div>
      </div>
    </div>
  </div>

  <div *ngIf="partidaSel" class="card sec destaque">
    <div class="head2">
      <h2>Estatísticas da Partida
        <span class="pill">{{ partidaSel.data }}</span>
        <span class="vs">{{ detalhe?.clubeNome }} {{ partidaSel.golsFeitos }} × {{ partidaSel.golsSofridos }} {{ partidaSel.adversario }}</span>
      </h2>
      <button class="btn-ghost" (click)="partidaSel=undefined">Fechar</button>
    </div>
    <div class="cabec">
      <span class="lado clube">{{ detalhe?.clubeNome }}</span>
      <span class="muted center">{{ partidaSel.emCasa ? '(em casa)' : '(fora)' }}</span>
      <span class="lado adv">{{ partidaSel.adversario }}</span>
    </div>
    <div *ngFor="let cat of categorias">
      <h3>{{ cat }}</h3>
      <div class="cmp" *ngFor="let m of camposPorCategoria(cat)">
        <span class="ca" [class.win]="valClube(m.campo) > valAdv(m.campo)">{{ valClube(m.campo) | number:'1.0-2' }}</span>
        <div class="bar">
          <div class="fill casa" [style.width.%]="pct(valClube(m.campo), valAdv(m.campo))"></div>
          <span class="lbl">{{ m.rotulo }}</span>
          <div class="fill fora" [style.width.%]="pct(valAdv(m.campo), valClube(m.campo))"></div>
        </div>
        <span class="fo" [class.win]="valAdv(m.campo) > valClube(m.campo)">{{ valAdv(m.campo) | number:'1.0-2' }}</span>
      </div>
    </div>
  </div>
  `,
  styles: [`
    .head { display: flex; align-items: center; gap: 14px; }
    .filtros { display: flex; gap: 6px; align-items: center; }
    .lim { color: var(--text-dim); display: flex; align-items: center; gap: 5px; margin-left: 8px; }
    .resumo { display: flex; gap: 30px; padding: 16px 20px; margin: 16px 0; }
    .metric { display: flex; flex-direction: column; }
    .metric b { font-size: 24px; }
    .metric small { color: var(--text-dim); }
    .cols { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    .sec { padding: 16px; }
    .mini { font-size: 12px; margin: 0 0 8px; }
    .linha { cursor: pointer; }
    .linha:hover td { background: var(--surface-2); }
    .linha.ativa td { background: rgba(62,166,255,.15); }
    .stat { display: flex; justify-content: space-between; padding: 5px 0; border-bottom: 1px dashed var(--border); }
    .rot { color: var(--text-dim); }
    .val { font-weight: 700; }
    .destaque { margin-top: 16px; outline: 1px solid var(--accent); }
    .head2 { display: flex; align-items: center; justify-content: space-between; }
    .vs { font-size: 13px; color: var(--text-dim); margin-left: 8px; }
    .cabec { display: grid; grid-template-columns: 1fr auto 1fr; margin: 8px 0 14px; }
    .lado { font-weight: 800; }
    .lado.clube { color: var(--accent); }
    .lado.adv { color: var(--accent-2); text-align: right; }
    .cmp { display: grid; grid-template-columns: 50px 1fr 50px; align-items: center; gap: 8px; margin: 4px 0; }
    .ca, .fo { font-weight: 700; }
    .ca { text-align: right; } .fo { text-align: left; }
    .ca.win, .fo.win { color: var(--win); }
    .bar { display: grid; grid-template-columns: 1fr auto 1fr; align-items: center; gap: 6px; }
    .fill { height: 7px; border-radius: 4px; background: var(--surface-2); min-width: 2px; }
    .fill.casa { justify-self: end; background: var(--accent); }
    .fill.fora { justify-self: start; background: var(--accent-2); }
    .lbl { font-size: 11px; color: var(--text-dim); white-space: nowrap; }
    @media (max-width: 900px) { .cols { grid-template-columns: 1fr; } }
  `]
})
export class ClubeDetalheComponent implements OnInit {
  clubeId!: number;
  detalhe?: ClubeDetalhe;
  filtro = 'TODOS';
  limite = 5;
  categorias = CATEGORIAS;
  campos: StatMeta[] = STAT_FIELDS;
  partidaSel?: PartidaResumo;
  private advStats: { [campo: string]: number } = {};
  private debounce?: any;

  constructor(private route: ActivatedRoute, private router: Router, private partidaSvc: PartidaService) {}

  ngOnInit() {
    this.clubeId = Number(this.route.snapshot.paramMap.get('id'));
    this.carregar();
  }

  carregar() {
    this.partidaSvc.detalheClube(this.clubeId, this.filtro, this.limite).subscribe(d => {
      this.detalhe = d;
      this.partidaSel = undefined;
    });
  }
  setFiltro(f: string) { this.filtro = f; this.carregar(); }
  onLimite(v: number) { this.limite = v; clearTimeout(this.debounce); this.debounce = setTimeout(() => this.carregar(), 400); }

  camposPorCategoria(cat: string): StatMeta[] { return this.campos.filter(c => c.categoria === cat); }
  media(campo: string): number { return this.detalhe?.medias[campo] ?? 0; }

  selecionarPartida(p: PartidaResumo) {
    this.partidaSel = p;
    this.advStats = {};
    this.partidaSvc.listar().subscribe(ps => {
      const full = ps.find(x => x.id === p.partidaId);
      if (full?.estatistica) {
        this.advStats = p.emCasa ? full.estatistica.fora : full.estatistica.casa;
      }
    });
  }

  valClube(campo: string): number { return this.partidaSel?.estatisticas?.[campo] ?? 0; }
  valAdv(campo: string): number { return this.advStats[campo] ?? 0; }
  pct(a: number, b: number): number { const t = a + b; return t === 0 ? 50 : (a / t) * 100; }

  voltar() { this.router.navigate(['/dashboard']); }
}
