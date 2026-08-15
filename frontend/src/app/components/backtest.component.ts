import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CampeonatoService } from '../services/campeonato.service';
import { BacktestService } from '../services/backtest.service';
import { Campeonato } from '../models/entities.model'; 
import {
  CalibracaoResultado, Faixa, LinhaMercado, RelatorioBacktest, Varredura
} from '../models/backtest.model';

/**
 * BACKTEST E CALIBRAÇÃO.
 *
 * A tela responde uma pergunta só: quando o modelo diz 60%, acontece 60%?
 *
 * O elemento central é o gráfico de confiabilidade, e ele foi desenhado para
 * impedir um erro específico que já custou caro neste projeto: olhar um ponto
 * fora da diagonal e concluir que o modelo errou.
 *
 * Cada faixa vira um ponto COM BARRA DE INCERTEZA vertical. A barra é
 * ±1,96·sqrt(p(1-p)/n) — o quanto a frequência observada balança só por
 * sorteio, com aquele número de jogos. Faixa com 4 jogos tem barra enorme;
 * faixa com 200 tem barra curta. Ponto cuja barra cruza a diagonal é
 * indistinguível de calibração perfeita, e é pintado como tal.
 *
 * Sem isso, uma faixa de 3 jogos com frequência 0% parece um desastre quando
 * na verdade não diz nada.
 */
@Component({
  selector: 'app-backtest',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
  <div class="wrap">

    <!-- ============ controles ============ -->
    <div class="card">
      <div class="secHd">
        <h3>Backtest</h3>
        <span class="spacer"></span>
        <span class="pill" *ngIf="rodando">rodando…</span>
      </div>

      <p class="hint">
        Cada partida é prevista usando <b>apenas</b> as partidas anteriores a ela.
        Vários campeonatos são ajustados separadamente e a avaliação é somada.
      </p>

      <div class="camps">
        <button *ngFor="let c of campeonatos"
                class="chip" [class.on]="selecionados.has(c.id!)"
                (click)="alternar(c.id!)">{{ c.nome }}</button>
        <span *ngIf="!campeonatos.length" class="vazio">
          Nenhum campeonato cadastrado. Importe partidas primeiro.
        </span>
      </div>

      <div class="params">
        <label>Modelo
          <select [(ngModel)]="modelo">
            <option value="DIXON_COLES">Dixon-Coles</option>
            <option value="POISSON">Poisson</option>
            <option value="BIVARIATE">Bivariate</option>
            <option value="NEG_BIN">Binomial Negativa</option>
          </select>
        </label>
        <label>Aquecimento
          <input type="number" [(ngModel)]="aquecimento" min="20" step="10">
        </label>
        <label>Reajuste a cada
          <input type="number" [(ngModel)]="passo" min="1" step="1">
        </label>
        <span class="spacer"></span>
        <button class="acao" [disabled]="rodando || !selecionados.size"
                (click)="rodar()">Rodar backtest</button>
        <button class="acao alt" [disabled]="rodando || !selecionados.size"
                (click)="rodarVarredura()">Varrer hiperparâmetros</button>
      </div>

      <p class="erro" *ngIf="erro">{{ erro }}</p>
    </div>

    <!-- ============ resumo ============ -->
    <div class="card" *ngIf="rel">
      <div class="secHd">
        <h3>{{ rel.partidasAvaliadas }} partidas avaliadas fora da amostra</h3>
        <span class="spacer"></span>
        <span class="pill">{{ rel.modelo }}</span>
      </div>

      <div class="nums">
        <div class="num">
          <span class="rot">resolução</span>
          <span class="val">±{{ rel.resolucao | number:'1.3-3' }}</span>
          <span class="sub">menor efeito visível</span>
        </div>
        <div class="num">
          <span class="rot">penalidade</span>
          <span class="val">{{ rel.penalidade | number:'1.1-1' }}</span>
          <span class="sub">regularização</span>
        </div>
        <div class="num" [class.alerta]="rel.rhoNaBorda > 0">
          <span class="rot">rho</span>
          <span class="val">{{ rel.rhoMedio | number:'1.3-3' }}</span>
          <span class="sub">{{ rel.rhoNaBorda }} de {{ rel.reajustes }} na borda</span>
        </div>
        <div class="num">
          <span class="rot">tempo</span>
          <span class="val">{{ (rel.duracaoMs / 1000) | number:'1.1-1' }}s</span>
          <span class="sub">{{ rel.duracaoMsPorReajuste }}ms por ajuste</span>
        </div>
      </div>

      <p class="leitura">{{ rel.leitura }}</p>

      <p class="hint" *ngIf="rel.rhoNaBorda > 0">
        <b>rho encostando no limite</b> em {{ rel.rhoNaBorda }} ajuste(s). Não é uma
        estimativa: é o modelo usando rho para compensar algo que rho não descreve.
      </p>

      <div class="camplist">
        <span *ngFor="let c of rel.campeonatos">{{ c }}</span>
      </div>
    </div>

    <!-- ============ mercados ============ -->
    <div class="card" *ngIf="linhas.length">
      <div class="secHd">
        <h3>Por mercado</h3>
        <span class="spacer"></span>
        <span class="pill">clique para ver a curva</span>
      </div>

      <table class="tab">
        <thead>
          <tr>
            <th>mercado</th>
            <th class="r">taxa base</th>
            <th class="c" [title]="'Brier Skill Score contra chutar a taxa base'">
              BSS e intervalo de 95%
            </th>
            <th class="r">ECE / ruído</th>
            <th class="r">viés</th>
            <th>leitura</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let l of linhas" (click)="selecionar(l)"
              [class.sel]="mercadoAberto === l.codigo">
            <td class="mono">{{ l.codigo }}</td>
            <td class="r dim">{{ l.res.taxaBase * 100 | number:'1.1-1' }}%</td>

            <!-- barra de intervalo: zero-centrada, escala fixa -->
            <td class="c">
              <svg class="ci" viewBox="0 0 200 22" preserveAspectRatio="none">
                <line x1="100" y1="2" x2="100" y2="20" class="zero"/>
                <line [attr.x1]="x(l.iv?.inferior)" y1="11"
                      [attr.x2]="x(l.iv?.superior)" y2="11"
                      class="barra" [class.conc]="l.conclusivo"/>
                <line [attr.x1]="x(l.iv?.inferior)" y1="6"
                      [attr.x2]="x(l.iv?.inferior)" y2="16" class="cap"/>
                <line [attr.x1]="x(l.iv?.superior)" y1="6"
                      [attr.x2]="x(l.iv?.superior)" y2="16" class="cap"/>
                <circle [attr.cx]="x(l.res.brierSkillScore)" cy="11" r="3.5"
                        class="ponto" [class.pos]="l.res.brierSkillScore > 0"/>
              </svg>
              <span class="bss" [class.pos]="l.res.brierSkillScore > 0">
                {{ l.res.brierSkillScore > 0 ? '+' : ''
                }}{{ l.res.brierSkillScore | number:'1.3-3' }}
              </span>
            </td>

            <td class="r" [class.ruim]="l.eceRelativo > 2">
              {{ l.eceRelativo | number:'1.2-2' }}×
            </td>
            <td class="r dim">
              {{ l.res.vies > 0 ? '+' : '' }}{{ l.res.vies * 100 | number:'1.1-1' }}
            </td>
            <td class="ver" [class.conc]="l.conclusivo">{{ l.veredito }}</td>
          </tr>
        </tbody>
      </table>

      <p class="hint">
        A barra é o intervalo de 95% do BSS. Enquanto ela cruzar a linha do zero, aquele
        mercado é <b>indistinguível de chutar a taxa base</b> — nem melhor, nem pior.
        Viés em pontos percentuais: positivo é o modelo prevendo demais.
      </p>
    </div>

    <!-- ============ curva de confiabilidade ============ -->
    <div class="card" *ngIf="faixas.length">
      <div class="secHd">
        <h3>Curva de confiabilidade — <span class="mono">{{ mercadoAberto }}</span></h3>
        <span class="spacer"></span>
        <span class="pill">{{ dentro }} de {{ faixas.length }} faixas dentro do ruído</span>
      </div>

      <div class="plot">
        <svg viewBox="0 0 420 420" class="curva">
          <!-- grade -->
          <g class="grade">
            <line *ngFor="let t of ticks" [attr.x1]="px(t)" y1="10"
                  [attr.x2]="px(t)" y2="370"/>
            <line *ngFor="let t of ticks" x1="50" [attr.y1]="py(t)"
                  x2="410" [attr.y2]="py(t)"/>
          </g>

          <!-- diagonal: calibração perfeita -->
          <line [attr.x1]="px(0)" [attr.y1]="py(0)"
                [attr.x2]="px(1)" [attr.y2]="py(1)" class="diag"/>

          <!-- barras de incerteza e pontos -->
          <g *ngFor="let f of faixas">
            <line [attr.x1]="px(f.pMedia)" [attr.y1]="py(clamp(f.frequenciaReal - erroFaixa(f)))"
                  [attr.x2]="px(f.pMedia)" [attr.y2]="py(clamp(f.frequenciaReal + erroFaixa(f)))"
                  class="whisk" [class.fora]="!dentroDoRuido(f)"/>
            <circle [attr.cx]="px(f.pMedia)" [attr.cy]="py(f.frequenciaReal)"
                    [attr.r]="raio(f)" class="pt" [class.fora]="!dentroDoRuido(f)">
              <title>{{ f.n }} jogos · previsto {{ f.pMedia*100 | number:'1.1-1' }}% · real {{ f.frequenciaReal*100 | number:'1.1-1' }}%</title>
            </circle>
          </g>

          <!-- eixos -->
          <text x="230" y="405" class="eixo">probabilidade prevista</text>
          <text x="14" y="190" class="eixo" transform="rotate(-90 14 190)">frequência observada</text>
          <g class="rot">
            <text *ngFor="let t of ticks" [attr.x]="px(t)" y="386" text-anchor="middle">
              {{ t*100 }}
            </text>
            <text *ngFor="let t of ticks" x="44" [attr.y]="py(t)+4" text-anchor="end">
              {{ t*100 }}
            </text>
          </g>
        </svg>

        <div class="legenda">
          <p>
            Cada ponto é uma faixa de probabilidade; o tamanho é o número de jogos.
            A barra vertical é o quanto a frequência observada balança <b>só por sorteio</b>
            com aquele tamanho de amostra.
          </p>
          <p>
            <span class="sw ok"></span> barra cruza a diagonal — indistinguível de
            calibração perfeita<br>
            <span class="sw fora"></span> barra não cruza — desvio maior que o ruído
          </p>
          <p class="hint">
            Faixa com poucos jogos tem barra enorme. Um ponto longe da diagonal com barra
            longa não é erro do modelo, é falta de dado.
          </p>

          <table class="mini">
            <tr><th>faixa</th><th class="r">n</th><th class="r">prev.</th><th class="r">real</th></tr>
            <tr *ngFor="let f of faixas" [class.fora]="!dentroDoRuido(f)">
              <td class="mono">{{ f.de*100 }}–{{ f.ate*100 }}%</td>
              <td class="r">{{ f.n }}</td>
              <td class="r">{{ f.pMedia*100 | number:'1.1-1' }}</td>
              <td class="r">{{ f.frequenciaReal*100 | number:'1.1-1' }}</td>
            </tr>
          </table>
        </div>
      </div>
    </div>

    <!-- ============ varredura ============ -->
    <div class="card" *ngIf="varr">
      <div class="secHd">
        <h3>Varredura de hiperparâmetros</h3>
        <span class="spacer"></span>
        <span class="pill">{{ varr.linhas.length }} configurações</span>
      </div>

      <p class="alerta-box">{{ varr.aviso }}</p>

      <table class="tab">
        <thead>
          <tr>
            <th class="r">penalidade</th>
            <th class="r">decaimento</th>
            <th class="r">BSS médio</th>
            <th class="c">perfil</th>
            <th class="r">ECE / ruído</th>
            <th class="r">rho</th>
            <th class="r">na borda</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let l of varr.linhas" [class.melhor]="l === melhorLinha">
            <td class="r mono">{{ l.penalidade }}</td>
            <td class="r mono">{{ l.xi }}</td>
            <td class="r bss" [class.pos]="l.bssMedio > 0">
              {{ l.bssMedio > 0 ? '+' : '' }}{{ l.bssMedio | number:'1.4-4' }}
            </td>
            <td class="c">
              <svg class="ci" viewBox="0 0 200 18" preserveAspectRatio="none">
                <line x1="100" y1="2" x2="100" y2="16" class="zero"/>
                <rect [attr.x]="barraX(l.bssMedio)" y="6"
                      [attr.width]="barraW(l.bssMedio)" height="6"
                      class="barraV" [class.pos]="l.bssMedio > 0"/>
              </svg>
            </td>
            <td class="r">{{ l.eceRelativoMedio | number:'1.2-2' }}×</td>
            <td class="r dim">{{ l.rhoMedio | number:'1.3-3' }}</td>
            <td class="r" [class.ruim]="l.rhoNaBorda > 4">{{ l.rhoNaBorda }}</td>
          </tr>
        </tbody>
      </table>

      <p class="hint">
        Procure <b>platô</b>, não pico. Uma região larga de configurações boas é sinal
        real; um máximo isolado cercado de vales é sorte, e escolher por ele infla o
        resultado em ~{{ varr.vieselecao | number:'1.3-3' }} de BSS.
      </p>
    </div>

  </div>
  `,
  styles: [`
    .wrap { display: flex; flex-direction: column; gap: 14px; }
    .card { background: var(--surface); border: 1px solid var(--border);
            border-radius: var(--radius); padding: 14px; box-shadow: var(--shadow); }
    .secHd { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
    .secHd h3 { margin: 0; font-size: 15px; }
    .spacer { flex: 1; }
    .pill { padding: 2px 8px; font-size: 11px; border-radius: 999px;
            background: var(--surface-2); color: var(--text-dim); }
    .hint { font-size: 12px; color: var(--text-dim); margin: 8px 0 0; line-height: 1.5; }
    .erro { color: var(--loss); font-size: 13px; margin: 10px 0 0; }
    .vazio { font-size: 12px; color: var(--text-dim); }

    /* seleção de campeonatos */
    .camps { display: flex; flex-wrap: wrap; gap: 6px; margin: 10px 0; }
    .chip { padding: 5px 11px; font-size: 12px; border-radius: 999px;
            background: var(--surface-2); color: var(--text-dim);
            border: 1px solid transparent; cursor: pointer; }
    .chip.on { background: var(--accent); color: #06121f; font-weight: 700; }

    .params { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 10px; }
    .params label { display: flex; flex-direction: column; gap: 3px;
                    font-size: 11px; color: var(--text-dim); }
    .params input, .params select { background: var(--surface-2); color: var(--text);
            border: 1px solid var(--border); border-radius: 6px;
            padding: 5px 8px; font-size: 13px; }
    .params input { width: 90px; }
    .acao { padding: 7px 14px; font-size: 13px; font-weight: 600; border-radius: 8px;
            background: var(--accent); color: #06121f; cursor: pointer; border: none; }
    .acao.alt { background: var(--accent-2); color: #f2f0ff; }
    .acao:disabled { opacity: .4; cursor: not-allowed; }

    /* números do resumo */
    .nums { display: flex; flex-wrap: wrap; gap: 10px; margin: 4px 0 10px; }
    .num { flex: 1 1 120px; background: var(--surface-2); border-radius: 8px;
           padding: 8px 10px; display: flex; flex-direction: column; gap: 1px;
           border-left: 3px solid var(--border); }
    .num.alerta { border-left-color: var(--draw); }
    .num .rot { font-size: 10px; text-transform: uppercase; letter-spacing: .5px;
                color: var(--text-dim); }
    .num .val { font-size: 19px; font-weight: 700; font-variant-numeric: tabular-nums; }
    .num .sub { font-size: 10px; color: var(--text-dim); }
    .leitura { font-size: 13px; line-height: 1.55; margin: 0;
               padding: 10px 12px; background: var(--surface-2);
               border-left: 3px solid var(--accent); border-radius: 6px; }
    .camplist { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
    .camplist span { font-size: 11px; color: var(--text-dim); }
    .alerta-box { font-size: 12px; line-height: 1.55; margin: 0 0 10px;
                  padding: 10px 12px; background: var(--surface-2);
                  border-left: 3px solid var(--draw); border-radius: 6px; }

    /* tabelas */
    .tab { width: 100%; border-collapse: collapse; font-size: 12px; }
    .tab th { text-align: left; font-weight: 600; color: var(--text-dim);
              font-size: 10px; text-transform: uppercase; letter-spacing: .4px;
              padding: 6px 8px; border-bottom: 1px solid var(--border); }
    .tab td { padding: 6px 8px; border-bottom: 1px solid var(--border); }
    .tab tbody tr { cursor: pointer; }
    .tab tbody tr:hover { background: var(--surface-2); }
    .tab tr.sel { background: var(--surface-2); }
    .tab tr.melhor td { background: rgba(62,166,255,.08); }
    .r { text-align: right; font-variant-numeric: tabular-nums; }
    .c { text-align: center; }
    .dim { color: var(--text-dim); }
    .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 11px; }
    .ruim { color: var(--loss); font-weight: 700; }
    .ver { font-size: 11px; color: var(--text-dim); }
    .ver.conc { color: var(--text); font-weight: 600; }
    .bss { font-variant-numeric: tabular-nums; font-weight: 600;
           color: var(--text-dim); margin-left: 6px; font-size: 11px; }
    .bss.pos { color: var(--win); }

    /* barra de intervalo */
    .ci { width: 130px; height: 22px; vertical-align: middle; }
    .ci .zero { stroke: var(--text-dim); stroke-width: 1; stroke-dasharray: 2 2; }
    .ci .barra { stroke: var(--text-dim); stroke-width: 2.5; }
    .ci .barra.conc { stroke: var(--win); }
    .ci .cap { stroke: var(--text-dim); stroke-width: 1.5; }
    .ci .ponto { fill: var(--text); }
    .ci .ponto.pos { fill: var(--win); }
    .ci .barraV { fill: var(--loss); opacity: .75; }
    .ci .barraV.pos { fill: var(--win); }

    /* curva de confiabilidade */
    .plot { display: flex; flex-wrap: wrap; gap: 18px; align-items: flex-start; }
    .curva { width: 420px; max-width: 100%; height: auto; }
    .curva .grade line { stroke: var(--border); stroke-width: 1; }
    .curva .diag { stroke: var(--text-dim); stroke-width: 1.5; stroke-dasharray: 5 4; }
    .curva .whisk { stroke: var(--accent); stroke-width: 2.5; opacity: .55;
                    stroke-linecap: round; }
    .curva .whisk.fora { stroke: var(--loss); opacity: .8; }
    .curva .pt { fill: var(--accent); stroke: var(--bg); stroke-width: 1.5; }
    .curva .pt.fora { fill: var(--loss); }
    .curva .eixo { fill: var(--text-dim); font-size: 11px; text-anchor: middle; }
    .curva .rot text { fill: var(--text-dim); font-size: 9px; }

    .legenda { flex: 1 1 240px; font-size: 12px; color: var(--text-dim); line-height: 1.55; }
    .legenda p { margin: 0 0 10px; }
    .sw { display: inline-block; width: 10px; height: 10px; border-radius: 50%;
          margin-right: 5px; vertical-align: -1px; }
    .sw.ok { background: var(--accent); }
    .sw.fora { background: var(--loss); }
    .mini { width: 100%; border-collapse: collapse; font-size: 11px; margin-top: 6px; }
    .mini th { text-align: left; color: var(--text-dim); font-weight: 600;
               padding: 3px 6px; border-bottom: 1px solid var(--border); }
    .mini td { padding: 3px 6px; border-bottom: 1px solid var(--border); }
    .mini tr.fora td { color: var(--loss); }

    @media (max-width: 720px) {
      .curva { width: 100%; }
      .ci { width: 90px; }
    }
  `]
})
export class BacktestComponent implements OnInit {

  campeonatos: Campeonato[] = [];
  selecionados = new Set<number>();

  modelo = 'DIXON_COLES';
  aquecimento = 60;
  passo = 10;

  rodando = false;
  erro = '';

  rel?: RelatorioBacktest;
  varr?: Varredura;
  linhas: LinhaMercado[] = [];
  mercadoAberto = '';
  faixas: Faixa[] = [];

  ticks = [0, 0.2, 0.4, 0.6, 0.8, 1];

  constructor(private campSvc: CampeonatoService,
              private backSvc: BacktestService) {}

  ngOnInit() {
    this.campSvc.listar().subscribe(cs => this.campeonatos = cs);
  }

  alternar(id: number) {
    if (this.selecionados.has(id)) this.selecionados.delete(id);
    else this.selecionados.add(id);
  }

  rodar() {
    this.rodando = true;
    this.erro = '';
    this.varr = undefined;
    this.backSvc.agregado([...this.selecionados], this.modelo, this.aquecimento, this.passo)
      .subscribe({
        next: r => { this.rel = r; this.montarLinhas(r); this.rodando = false; },
        error: e => {
          this.erro = e?.error?.mensagem || 'O backtest não completou. Verifique se os campeonatos têm histórico suficiente.';
          this.rodando = false;
        }
      });
  }

  rodarVarredura() {
    this.rodando = true;
    this.erro = '';
    this.backSvc.varredura([...this.selecionados], '0,3,8,16,30', '0,0.005',
                           this.modelo, this.aquecimento, this.passo)
      .subscribe({
        next: v => { this.varr = v; this.rodando = false; },
        error: e => {
          this.erro = e?.error?.mensagem || 'A varredura não completou.';
          this.rodando = false;
        }
      });
  }

  // ------------------------------------------------------------------

  private montarLinhas(r: RelatorioBacktest) {
    this.linhas = Object.keys(r.porMercado).map(codigo => {
      const res = r.porMercado[codigo];
      const iv = r.intervalos ? r.intervalos[codigo] : undefined;
      const eceRel = res.eceRuido > 0 ? res.ece / res.eceRuido : 0;
      const conclusivo = !!iv && (iv.inferior > 0 || iv.superior < 0);
      return { codigo, res, iv, eceRelativo: eceRel, conclusivo,
               veredito: this.veredito(res, iv, conclusivo) };
    });
    // pior primeiro: o que precisa de atenção não deve exigir rolagem
    this.linhas.sort((a, b) => a.res.brierSkillScore - b.res.brierSkillScore);
    if (this.linhas.length) this.selecionar(this.linhas[this.linhas.length - 1]);
  }

  private veredito(res: CalibracaoResultado, iv: { inferior: number; superior: number } | undefined,
                   conclusivo: boolean): string {
    if (res.n < 100) return `amostra curta (${res.n})`;
    if (!conclusivo) return 'indistinguível da taxa base';
    return iv && iv.inferior > 0 ? 'melhor que a taxa base' : 'pior que a taxa base';
  }

  selecionar(l: LinhaMercado) {
    this.mercadoAberto = l.codigo;
    this.faixas = l.res.faixas || [];
  }

  get dentro(): number {
    return this.faixas.filter(f => this.dentroDoRuido(f)).length;
  }

  get melhorLinha() {
    if (!this.varr || !this.varr.linhas.length) return null;
    return this.varr.linhas.reduce((a, b) => b.bssMedio > a.bssMedio ? b : a);
  }

  // ---- geometria do gráfico ----

  /** Meia-largura do intervalo de 95% da frequência observada numa faixa. */
  erroFaixa(f: Faixa): number {
    if (f.n <= 0) return 0;
    const p = Math.min(0.999, Math.max(0.001, f.pMedia));
    return 1.96 * Math.sqrt(p * (1 - p) / f.n);
  }

  /** A barra de incerteza alcança a diagonal? Se sim, o desvio é ruído. */
  dentroDoRuido(f: Faixa): boolean {
    return Math.abs(f.frequenciaReal - f.pMedia) <= this.erroFaixa(f);
  }

  raio(f: Faixa): number {
    const total = this.faixas.reduce((s, x) => s + x.n, 0) || 1;
    return 3 + 9 * Math.sqrt(f.n / total);
  }

  clamp(v: number): number { return Math.min(1, Math.max(0, v)); }

  /** x do gráfico: 50..410 para probabilidade 0..1 */
  px(p: number): number { return 50 + p * 360; }
  /** y do gráfico: invertido, 370..10 */
  py(p: number): number { return 370 - p * 360; }

  /** x da barra de intervalo do BSS: zero no centro, escala fixa de ±0.10 */
  x(v: number | undefined): number {
    const escala = 0.10;
    const c = Math.min(escala, Math.max(-escala, v ?? 0));
    return 100 + (c / escala) * 96;
  }

  barraX(bss: number): number { return bss >= 0 ? 100 : this.x(bss); }
  barraW(bss: number): number { return Math.abs(this.x(bss) - 100); }
}