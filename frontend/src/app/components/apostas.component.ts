import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import {
  Aposta, Cenario, CENARIOS, NOME_CENARIO,
  analisar, coberturaCompleta, book, margemCasa, temArbitragem,
  AnaliseCarteira, PlanoCompleto
} from '../models/aposta.model';

@Component({
  selector: 'app-apostas',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
  <div class="head">
    <div>
      <h1>Controle de apostas</h1>
      <p class="muted">Registre suas posições e simule coberturas com odds ao vivo.</p>
    </div>
  </div>

  <!-- AVISO — visível, não escondido -->
  <div class="card aviso">
    <b>⚠ Leia antes de usar</b>
    <p>
      Esta ferramenta calcula o resultado <b>real</b> de uma cobertura — ela não promete zerar prejuízo,
      porque na maioria das situações isso é <b>matematicamente impossível</b>.
    </p>
    <ul>
      <li>
        A soma de 1/odd de todos os resultados é o <b>book</b>. Se <b>book &gt; 1</b> (o caso normal),
        a diferença é a margem da casa e <b>nenhuma combinação de stakes garante lucro</b>.
      </li>
      <li>
        Cobrir uma aposta perdendo <b>não elimina a perda</b>: ela apenas distribui a perda entre os
        cenários. Você troca "talvez perca muito" por "perco isto com certeza".
      </li>
      <li>
        Aumentar o stake a cada virada (<b>martingale</b>) faz a exposição crescer rápido e costuma
        terminar em perda maior, não em prejuízo zero.
      </li>
    </ul>
    <p class="mini">
      Se as apostas deixaram de ser lazer — perseguir perdas, apostar o que não pode perder, esconder de
      alguém — vale conversar com alguém de confiança. No Brasil, o CVV atende 24h no <b>188</b>
      (ligação gratuita) e em cvv.org.br.
    </p>
  </div>

  <div class="cols">
    <!-- ============ POSIÇÕES ============ -->
    <div class="card sec">
      <h2>Minhas apostas</h2>

      <div class="form">
        <label>
          <small>Resultado</small>
          <select [(ngModel)]="novoCenario">
            <option *ngFor="let c of cenarios" [value]="c">{{ nome(c) }}</option>
          </select>
        </label>
        <label>
          <small>Odd</small>
          <input type="number" step="0.01" min="1.01" [(ngModel)]="novaOdd" placeholder="2.00">
        </label>
        <label>
          <small>Valor (R$)</small>
          <input type="number" step="1" min="0" [(ngModel)]="novoStake" placeholder="100">
        </label>
        <label class="grow">
          <small>Nota</small>
          <input [(ngModel)]="novaNota" placeholder="pré-jogo, aos 30'…">
        </label>
        <button class="btn-primary" (click)="adicionar()" [disabled]="!podeAdicionar()">Adicionar</button>
      </div>

      <table class="tap" *ngIf="apostas.length; else semAp">
        <thead>
          <tr><th>Resultado</th><th class="c">Odd</th><th class="c">Valor</th><th class="c">Retorno</th><th>Nota</th><th></th></tr>
        </thead>
        <tbody>
          <tr *ngFor="let a of apostas">
            <td><span class="badge" [ngClass]="a.cenario.toLowerCase()">{{ nome(a.cenario) }}</span></td>
            <td class="c">{{ a.odd | number:'1.2-2' }}</td>
            <td class="c">{{ a.stake | currency:'BRL' }}</td>
            <td class="c muted">{{ a.stake * a.odd | currency:'BRL' }}</td>
            <td class="mini muted">{{ a.nota }}</td>
            <td class="c"><button class="btn-danger mini" (click)="remover(a)">✕</button></td>
          </tr>
        </tbody>
        <tfoot>
          <tr><td colspan="2"><b>Total investido</b></td>
              <td class="c"><b>{{ analise().investido | currency:'BRL' }}</b></td>
              <td colspan="3"></td></tr>
        </tfoot>
      </table>
      <ng-template #semAp><p class="muted mini">Nenhuma aposta registrada.</p></ng-template>

      <div *ngIf="apostas.length" class="resumo">
        <h3>Se o jogo terminar agora</h3>
        <div class="cen" *ngFor="let c of analise().cenarios">
          <span class="badge" [ngClass]="c.cenario.toLowerCase()">{{ nome(c.cenario) }}</span>
          <span class="spacer"></span>
          <span class="muted mini">retorno {{ c.retorno | currency:'BRL' }}</span>
          <span class="lucro" [class.pos]="c.lucro > 0" [class.neg]="c.lucro < 0">
            {{ c.lucro > 0 ? '+' : '' }}{{ c.lucro | currency:'BRL' }}
          </span>
        </div>
      </div>
    </div>

    <!-- ============ COBERTURA ============ -->
    <div class="card sec">
      <h2>Cobertura ao vivo</h2>
      <p class="muted mini">Informe as odds atuais. O cálculo mostra o resultado real de cobrir.</p>

      <div class="oddsLive">
        <label *ngFor="let c of cenarios">
          <small>{{ nome(c) }}</small>
          <input type="number" step="0.01" min="1.01" [(ngModel)]="oddsLive[c]">
        </label>
      </div>

      <!-- diagnóstico do book -->
      <div class="bookBox" [class.arb]="arbitragem()" [class.mar]="!arbitragem()">
        <div class="bkRow">
          <span>Book (soma de 1/odd)</span>
          <span class="spacer"></span>
          <b>{{ bookAtual() | number:'1.4-4' }}</b>
        </div>
        <div class="bkRow">
          <span>Margem da casa</span>
          <span class="spacer"></span>
          <b>{{ margem() | number:'1.2-2' }}%</b>
        </div>
        <p class="mini bkMsg">
          <ng-container *ngIf="arbitragem()">
            ✅ Book &lt; 1: existe arbitragem — lucro garantido é possível aqui.
          </ng-container>
          <ng-container *ngIf="!arbitragem()">
            ⚠ Book ≥ 1: a casa tem margem. <b>Nenhuma combinação de stakes garante lucro.</b>
            Toda cobertura apenas redistribui o resultado.
          </ng-container>
        </p>
      </div>

      <div *ngIf="apostas.length; else semPos">
        <h3>Cobertura completa (iguala todos os cenários)</h3>
        <table class="tap">
          <thead><tr><th>Apostar em</th><th class="c">Odd</th><th class="c">Stake sugerido</th></tr></thead>
          <tbody>
            <tr *ngFor="let c of cenarios">
              <td><span class="badge" [ngClass]="c.toLowerCase()">{{ nome(c) }}</span></td>
              <td class="c">{{ oddsLive[c] | number:'1.2-2' }}</td>
              <td class="c">
                <b *ngIf="plano().stakes[c] > 0.005">{{ plano().stakes[c] | currency:'BRL' }}</b>
                <span *ngIf="plano().stakes[c] <= 0.005" class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>

        <div class="veredito" [class.ok]="plano().garantido" [class.bad]="!plano().garantido">
          <div class="vRow">
            <span>Exposição total</span><span class="spacer"></span>
            <b>{{ plano().exposicaoTotal | currency:'BRL' }}</b>
          </div>
          <div class="vRow big">
            <span>Resultado travado</span><span class="spacer"></span>
            <b [class.pos]="plano().lucroGarantido >= 0" [class.neg]="plano().lucroGarantido < 0">
              {{ plano().lucroGarantido > 0 ? '+' : '' }}{{ plano().lucroGarantido | currency:'BRL' }}
            </b>
          </div>
          <p class="mini vMsg">{{ plano().mensagem }}</p>
        </div>

        <!-- comparação honesta: cobrir vs não cobrir -->
        <h3>Cobrir ou não cobrir?</h3>
        <table class="tap cmp">
          <thead><tr><th></th><th class="c">Não cobrir</th><th class="c">Cobrir</th></tr></thead>
          <tbody>
            <tr>
              <td>Melhor caso</td>
              <td class="c pos">{{ analise().melhorCaso | currency:'BRL' }}</td>
              <td class="c">{{ plano().lucroGarantido | currency:'BRL' }}</td>
            </tr>
            <tr>
              <td>Pior caso</td>
              <td class="c neg">{{ analise().piorCaso | currency:'BRL' }}</td>
              <td class="c">{{ plano().lucroGarantido | currency:'BRL' }}</td>
            </tr>
            <tr>
              <td>Exposição</td>
              <td class="c">{{ analise().investido | currency:'BRL' }}</td>
              <td class="c">{{ plano().exposicaoTotal | currency:'BRL' }}</td>
            </tr>
          </tbody>
        </table>
        <p class="muted mini">
          Cobrir troca a incerteza por um valor fixo. Se o valor travado for negativo,
          você está escolhendo <b>perder menos com certeza</b> — não evitando a perda.
        </p>

        <button class="btn-ghost" (click)="limpar()">Limpar tudo</button>
      </div>
      <ng-template #semPos>
        <p class="muted mini">Adicione ao menos uma aposta para calcular a cobertura.</p>
      </ng-template>
    </div>
  </div>
  `,
  styles: [`
    .head { display: flex; align-items: flex-end; gap: 16px; }
    .aviso { padding: 14px 18px; margin: 14px 0; border-left: 3px solid var(--draw);
             background: rgba(241,196,15,.07); }
    .aviso b { color: var(--draw); }
    .aviso p { margin: 8px 0; }
    .aviso ul { margin: 8px 0; padding-left: 20px; }
    .aviso li { margin: 4px 0; color: var(--text-dim); }
    .aviso .mini { color: var(--text-dim); font-size: 12px; border-top: 1px solid var(--border); padding-top: 8px; }
    .cols { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    .sec { padding: 16px; }
    .form { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 8px; margin-bottom: 14px; }
    .form label { display: flex; flex-direction: column; gap: 3px; }
    .form label.grow { flex: 1; min-width: 110px; }
    .form small { font-size: 10px; color: var(--text-dim); text-transform: uppercase; }
    .form input, .form select { width: 100%; }
    .form input[type=number] { width: 86px; }
    .tap { width: 100%; border-collapse: collapse; }
    .tap th { font-size: 10px; padding: 5px; }
    .tap td { padding: 6px 5px; border-bottom: 1px dashed var(--border); font-size: 12px; }
    .tap .c { text-align: center; }
    .tap tfoot td { border-top: 2px solid var(--border); border-bottom: none; padding-top: 8px; }
    .tap .mini { font-size: 11px; padding: 2px 6px; }
    .badge.v { background: rgba(46,204,113,.2); color: var(--win); }
    .badge.e { background: rgba(241,196,15,.2); color: var(--draw); }
    .badge.d { background: rgba(231,76,60,.2); color: var(--loss); }
    .resumo { margin-top: 16px; border-top: 2px solid var(--border); padding-top: 10px; }
    .cen { display: flex; align-items: center; gap: 8px; padding: 6px 0; border-bottom: 1px dashed var(--border); }
    .lucro { font-weight: 800; min-width: 92px; text-align: right; }
    .lucro.pos, .pos { color: var(--win); }
    .lucro.neg, .neg { color: var(--loss); }
    .oddsLive { display: flex; gap: 8px; margin: 10px 0; }
    .oddsLive label { display: flex; flex-direction: column; gap: 3px; flex: 1; }
    .oddsLive small { font-size: 10px; color: var(--text-dim); text-transform: uppercase; }
    .oddsLive input { width: 100%; }
    .bookBox { padding: 10px 12px; border-radius: 8px; margin: 10px 0; }
    .bookBox.arb { background: rgba(46,204,113,.1); border: 1px solid var(--win); }
    .bookBox.mar { background: rgba(241,196,15,.08); border: 1px solid var(--draw); }
    .bkRow { display: flex; align-items: center; font-size: 12px; padding: 2px 0; }
    .bkMsg { margin: 6px 0 0; color: var(--text-dim); }
    .veredito { padding: 12px; border-radius: 9px; margin: 12px 0; }
    .veredito.ok { background: rgba(46,204,113,.1); border: 1px solid var(--win); }
    .veredito.bad { background: rgba(231,76,60,.09); border: 1px solid var(--loss); }
    .vRow { display: flex; align-items: center; font-size: 12px; padding: 3px 0; }
    .vRow.big b { font-size: 20px; }
    .vMsg { margin: 8px 0 0; color: var(--text-dim); line-height: 1.5; }
    .cmp td:first-child { color: var(--text-dim); }
    @media (max-width: 900px) { .cols { grid-template-columns: 1fr; } .oddsLive { flex-wrap: wrap; } }
  `]
})
export class ApostasComponent implements OnInit {
  private readonly KEY = 'footballstats.apostas';

  cenarios = CENARIOS;
  apostas: Aposta[] = [];

  novoCenario: Cenario = 'V';
  novaOdd: number | null = null;
  novoStake: number | null = null;
  novaNota = '';

  oddsLive: Record<Cenario, number> = { V: 2.00, E: 3.20, D: 3.50 };

  ngOnInit() {
    const raw = localStorage.getItem(this.KEY);
    if (raw) {
      try { this.apostas = JSON.parse(raw); } catch { this.apostas = []; }
    }
  }

  private salvar() { localStorage.setItem(this.KEY, JSON.stringify(this.apostas)); }

  nome(c: Cenario): string { return NOME_CENARIO[c]; }

  podeAdicionar(): boolean {
    return !!this.novaOdd && this.novaOdd > 1 && !!this.novoStake && this.novoStake > 0;
  }

  adicionar() {
    if (!this.podeAdicionar()) return;
    this.apostas.push({
      id: Date.now(),
      cenario: this.novoCenario,
      odd: Number(this.novaOdd),
      stake: Number(this.novoStake),
      nota: this.novaNota.trim() || undefined
    });
    this.novaOdd = null; this.novoStake = null; this.novaNota = '';
    this.salvar();
  }

  remover(a: Aposta) {
    this.apostas = this.apostas.filter(x => x.id !== a.id);
    this.salvar();
  }

  limpar() {
    if (!confirm('Remover todas as apostas registradas?')) return;
    this.apostas = [];
    this.salvar();
  }

  analise(): AnaliseCarteira { return analisar(this.apostas); }
  plano(): PlanoCompleto { return coberturaCompleta(this.apostas, this.oddsLive); }
  bookAtual(): number { return book(this.oddsLive); }
  margem(): number { return margemCasa(this.oddsLive); }
  arbitragem(): boolean { return temArbitragem(this.oddsLive); }
}