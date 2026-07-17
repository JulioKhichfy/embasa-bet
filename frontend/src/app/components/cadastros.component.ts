import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Nacao, Campeonato, Clube, ImportCadastro } from '../models/entities.model';
import { NacaoService } from '../services/nacao.service';
import { CampeonatoService } from '../services/campeonato.service';
import { ClubeService } from '../services/clube.service';
import { PartidaService } from '../services/partida.service';

@Component({
  selector: 'app-cadastros',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
  <h1>Cadastros</h1>
  <p class="muted">Gerencie Nações, Campeonatos e Clubes.</p>

  <div class="card importbox">
    <div class="ibhd">
      <div>
        <b>📄 Importar campeonato.txt</b>
        <p class="muted mini">Uma linha por campeonato no formato <code>nação;campeonato;clube1;clube2;…</code>. Registros existentes são reutilizados.</p>
      </div>
      <label class="btn-primary uploadbtn">Escolher arquivo
        <input type="file" accept=".txt" hidden (change)="uploadTxt($event)">
      </label>
    </div>
    <div *ngIf="importResult" class="ibres">
      <span class="pill ok">{{ importResult.mensagem }}</span>
      <div *ngIf="importResult.avisos.length" class="avisos">
        <div *ngFor="let a of importResult.avisos" class="mini muted">⚠ {{ a }}</div>
      </div>
    </div>
  </div>

  <div class="grid3">
    <!-- NAÇÃO -->
    <div class="card sec">
      <h2>Nações</h2>
      <div class="row">
        <input [(ngModel)]="novaNacao" placeholder="Nome da nação" (keyup.enter)="criarNacao()">
        <button class="btn-primary" (click)="criarNacao()">Adicionar</button>
      </div>
      <ul class="lista">
        <li *ngFor="let n of nacoes" [class.sel]="nacaoSel?.id === n.id" (click)="selecionarNacao(n)">
          <span>{{ n.nome }}</span>
          <button class="btn-danger mini" (click)="excluirNacao(n, $event)">✕</button>
        </li>
        <li *ngIf="!nacoes.length" class="vazio">Nenhuma nação.</li>
      </ul>
    </div>

    <!-- CAMPEONATO -->
    <div class="card sec">
      <h2>Campeonatos <span class="pill" *ngIf="nacaoSel">{{ nacaoSel.nome }}</span></h2>
      <div *ngIf="nacaoSel; else escNacao">
        <div class="row">
          <input [(ngModel)]="novoCamp" placeholder="Nome do campeonato" (keyup.enter)="criarCamp()">
          <button class="btn-primary" (click)="criarCamp()">Adicionar</button>
        </div>
        <ul class="lista">
          <li *ngFor="let c of campeonatos" [class.sel]="campSel?.id === c.id" (click)="selecionarCamp(c)">
            <span>{{ c.nome }}</span>
            <button class="btn-danger mini" (click)="excluirCamp(c, $event)">✕</button>
          </li>
          <li *ngIf="!campeonatos.length" class="vazio">Nenhum campeonato.</li>
        </ul>
      </div>
      <ng-template #escNacao><p class="vazio">Selecione uma nação.</p></ng-template>
    </div>

    <!-- CLUBE -->
    <div class="card sec">
      <h2>Clubes <span class="pill" *ngIf="campSel">{{ campSel.nome }}</span></h2>
      <div *ngIf="campSel; else escCamp">
        <div class="row">
          <input [(ngModel)]="novoClube" placeholder="Nome do clube" (keyup.enter)="criarClube()">
          <button class="btn-primary" (click)="criarClube()">Adicionar</button>
        </div>
        <ul class="lista">
          <li *ngFor="let c of clubes">
            <span>{{ c.nome }}</span>
            <button class="btn-danger mini" (click)="excluirClube(c)">✕</button>
          </li>
          <li *ngIf="!clubes.length" class="vazio">Nenhum clube.</li>
        </ul>
      </div>
      <ng-template #escCamp><p class="vazio">Selecione um campeonato.</p></ng-template>
    </div>
  </div>
  `,
  styles: [`
    .grid3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-top: 16px; }
    .sec { padding: 16px; }
    .row { display: flex; gap: 8px; margin-bottom: 12px; }
    .row input { flex: 1; }
    .lista { list-style: none; margin: 0; padding: 0; }
    .lista li { display: flex; align-items: center; justify-content: space-between; padding: 9px 10px; border-radius: 8px; cursor: pointer; }
    .lista li:hover { background: var(--surface-2); }
    .lista li.sel { background: rgba(62,166,255,.15); outline: 1px solid var(--accent); }
    .lista li.vazio { color: var(--text-dim); cursor: default; justify-content: flex-start; }
    .lista li.vazio:hover { background: transparent; }
    .mini { padding: 2px 7px; font-size: 11px; }
    @media (max-width: 900px) { .grid3 { grid-template-columns: 1fr; } }
    .importbox { padding: 16px; margin: 16px 0; }
    .ibhd { display: flex; align-items: center; gap: 16px; }
    .ibhd .mini { margin: 4px 0 0; }
    .ibhd code { background: var(--surface-2); padding: 1px 6px; border-radius: 5px; }
    .uploadbtn { cursor: pointer; white-space: nowrap; }
    .ibres { margin-top: 12px; }
    .pill.ok { background: rgba(46,204,113,.2); color: var(--win); }
    .avisos { margin-top: 8px; max-height: 140px; overflow: auto; }
  `]
})
export class CadastrosComponent implements OnInit {
  nacoes: Nacao[] = [];
  campeonatos: Campeonato[] = [];
  clubes: Clube[] = [];

  nacaoSel?: Nacao;
  campSel?: Campeonato;

  novaNacao = '';
  novoCamp = '';
  novoClube = '';
  importResult?: ImportCadastro;

  constructor(
    private nacaoSvc: NacaoService,
    private campSvc: CampeonatoService,
    private clubeSvc: ClubeService,
    private partidaSvc: PartidaService
  ) {}

  ngOnInit() { this.carregarNacoes(); }

  uploadTxt(ev: Event) {
    const input = ev.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.partidaSvc.importarCadastro(file).subscribe({
      next: r => {
        this.importResult = r;
        this.carregarNacoes();
        if (this.nacaoSel) this.selecionarNacao(this.nacaoSel);
      },
      error: () => { this.importResult = { nacoesCriadas: 0, campeonatosCriados: 0, clubesCriados: 0, linhasProcessadas: 0, linhasIgnoradas: 0, avisos: ['Erro ao importar o arquivo.'], mensagem: 'Falha na importação.' }; }
    });
    input.value = '';
  }

  carregarNacoes() { this.nacaoSvc.listar().subscribe(n => this.nacoes = n); }

  selecionarNacao(n: Nacao) {
    this.nacaoSel = n; this.campSel = undefined; this.clubes = [];
    this.campSvc.listar(n.id).subscribe(c => this.campeonatos = c);
  }
  selecionarCamp(c: Campeonato) {
    this.campSel = c;
    this.clubeSvc.listar(c.id).subscribe(cl => this.clubes = cl);
  }

  criarNacao() {
    const nome = this.novaNacao.trim(); if (!nome) return;
    this.nacaoSvc.criar({ nome }).subscribe(() => { this.novaNacao = ''; this.carregarNacoes(); });
  }
  excluirNacao(n: Nacao, e: Event) {
    e.stopPropagation();
    if (!confirm(`Excluir nação "${n.nome}" e todo o conteúdo?`)) return;
    this.nacaoSvc.excluir(n.id!).subscribe(() => {
      if (this.nacaoSel?.id === n.id) { this.nacaoSel = undefined; this.campeonatos = []; this.clubes = []; }
      this.carregarNacoes();
    });
  }

  criarCamp() {
    const nome = this.novoCamp.trim(); if (!nome || !this.nacaoSel) return;
    this.campSvc.criar(this.nacaoSel.id!, { nome }).subscribe(() => { this.novoCamp = ''; this.selecionarNacao(this.nacaoSel!); });
  }
  excluirCamp(c: Campeonato, e: Event) {
    e.stopPropagation();
    if (!confirm(`Excluir campeonato "${c.nome}"?`)) return;
    this.campSvc.excluir(c.id!).subscribe(() => {
      if (this.campSel?.id === c.id) { this.campSel = undefined; this.clubes = []; }
      this.selecionarNacao(this.nacaoSel!);
    });
  }

  criarClube() {
    const nome = this.novoClube.trim(); if (!nome || !this.campSel) return;
    this.clubeSvc.criar(this.campSel.id!, { nome }).subscribe(() => { this.novoClube = ''; this.selecionarCamp(this.campSel!); });
  }
  excluirClube(c: Clube) {
    this.clubeSvc.contarPartidas(c.id!).subscribe(qtd => {
      const aviso = qtd > 0
        ? `Excluir clube "${c.nome}"?\n\nAtenção: ${qtd} partida(s) deste clube (e suas estatísticas) também serão removidas.`
        : `Excluir clube "${c.nome}"?`;
      if (!confirm(aviso)) return;
      this.clubeSvc.excluir(c.id!).subscribe(() => this.selecionarCamp(this.campSel!));
    });
  }
}
