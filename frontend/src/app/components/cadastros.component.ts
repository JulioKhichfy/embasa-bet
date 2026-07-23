import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Nacao, Campeonato, Clube, ImportCadastro, ImportLote, DuplicadoSugestao, FusaoResult } from '../models/entities.model';
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

  <!-- ============ UPLOAD GLOBAL DE PARTIDAS ============ -->
  <div class="card importbox">
    <div class="ibhd">
      <div>
        <b>⚽ Upload global de partidas (.html)</b>
        <p class="muted mini">
          Selecione <b>vários</b> arquivos de uma vez. O mandante de cada partida vem do próprio
          nome do arquivo, no padrão <code>Clube_&lt;id&gt;.html</code> — ex.:
          <code>Chapecoense_15237944.html</code> → mandante <b>Chapecoense</b>.
        </p>
      </div>
      <label class="btn-primary uploadbtn" [class.disabled]="!campSel || enviandoGlobal">
        {{ enviandoGlobal ? 'Enviando…' : 'Escolher arquivos' }}
        <input type="file" accept=".html,.htm" multiple hidden
               [disabled]="!campSel || enviandoGlobal" (change)="uploadGlobal($event)">
      </label>
    </div>

    <p *ngIf="!campSel" class="mini muted alerta">
      ⚠ Selecione um <b>campeonato</b> abaixo antes de enviar os arquivos.
    </p>
    <p *ngIf="campSel" class="mini muted">
      Destino: <span class="pill">{{ campSel.nome }}</span>
    </p>

    <div *ngIf="loteResult" class="ibres">
      <span class="pill" [class.ok]="loteResult.comErro === 0" [class.warn]="loteResult.comErro > 0">
        {{ loteResult.mensagem }}
      </span>
      <div class="lotelist">
        <div *ngFor="let r of loteResult.resultados" class="mini loteitem"
             [class.err]="!r.importada">
          <span class="ico">{{ r.importada ? '✓' : '✕' }}</span>
          <b>{{ r.nomeArquivo }}</b>
          <span *ngIf="r.importada">— {{ r.clubeCasa }} {{ r.golsCasa }} x {{ r.golsFora }} {{ r.clubeFora }} ({{ r.data }})</span>
          <span *ngIf="!r.importada" class="muted">— {{ r.mensagem }}</span>
        </div>
      </div>
    </div>
  </div>

  <!-- ============ FUSÃO DE CLUBES DUPLICADOS ============ -->
  <div class="card importbox">
    <div class="ibhd">
      <div>
        <b>🔗 Fundir clubes duplicados</b>
        <p class="muted mini">
          Quando a importação cria o mesmo clube com nomes diferentes
          (<i>Vasco</i> / <i>Vasco da Gama</i>), funda-os em um só. As partidas do clube removido
          passam para o mantido; duplicatas são descartadas.
        </p>
      </div>
      <button class="btn-ghost" (click)="carregarDuplicados()" [disabled]="!campSel">
        🔎 Procurar duplicados
      </button>
    </div>

    <p *ngIf="!campSel" class="mini muted alerta">⚠ Selecione um campeonato para fundir clubes.</p>

    <div *ngIf="campSel">
      <!-- sugestões automáticas -->
      <div *ngIf="duplicados?.length" class="sugs">
        <div class="mini muted">Possíveis duplicados encontrados:</div>
        <div *ngFor="let d of duplicados" class="sug">
          <span><b>{{ d.clubeANome }}</b> ({{ d.partidasA }}j)</span>
          <span class="muted">↔</span>
          <span><b>{{ d.clubeBNome }}</b> ({{ d.partidasB }}j)</span>
          <button class="btn-ghost mini" (click)="usarSugestao(d)">Usar</button>
        </div>
      </div>
      <div *ngIf="duplicados && !duplicados.length" class="mini muted">
        Nenhum duplicado óbvio encontrado. Você ainda pode escolher manualmente abaixo.
      </div>

      <!-- seleção manual -->
      <div class="fusaorow">
        <div class="fcol">
          <label class="mini muted">Manter (nome definitivo)</label>
          <select [(ngModel)]="manterId">
            <option [ngValue]="undefined">— selecione —</option>
            <option *ngFor="let c of clubes" [ngValue]="c.id">{{ c.nome }}</option>
          </select>
        </div>
        <div class="seta">←</div>
        <div class="fcol">
          <label class="mini muted">Remover (será absorvido)</label>
          <select [(ngModel)]="removerId">
            <option [ngValue]="undefined">— selecione —</option>
            <option *ngFor="let c of clubes" [ngValue]="c.id">{{ c.nome }}</option>
          </select>
        </div>
        <button class="btn-danger" [disabled]="!podeFundir() || fundindo" (click)="fundir()">
          {{ fundindo ? 'Fundindo…' : 'Fundir' }}
        </button>
      </div>

      <div *ngIf="fusaoResult" class="ibres">
        <span class="pill" [class.ok]="fusaoResult.ok" [class.warn]="!fusaoResult.ok">
          {{ fusaoResult.mensagem }}
        </span>
        <div *ngIf="fusaoResult.avisos?.length" class="avisos">
          <div *ngFor="let a of fusaoResult.avisos" class="mini muted">⚠ {{ a }}</div>
        </div>
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
        <p class="muted mini apehint">
          Apelidos ajudam a importação a reconhecer o clube quando o SofaScore usa
          outro nome (ex.: <i>Galo</i> ou <i>Athletico-MG</i> → <b>Atlético Mineiro</b>).
          Digite ao lado do clube e tecle Enter.
        </p>
        <ul class="lista clubes-ape">
          <li *ngFor="let c of clubes" class="clube-li">
            <div class="clube-hd">
              <span class="clube-nome">{{ c.nome }}</span>
              <span class="spacer"></span>
              <button class="btn-danger mini" (click)="excluirClube(c)" title="Excluir clube">✕</button>
            </div>
            <div class="apelinha">
              <span class="ape-chip" *ngFor="let ap of (c.apelidos || [])">
                {{ ap }}
                <button class="ape-x" (click)="removerApelido(c, ap)" title="Remover apelido">×</button>
              </span>
              <span *ngIf="!(c.apelidos || []).length" class="muted mini semape">sem apelidos</span>
              <input class="ape-input" [(ngModel)]="novoApelido[c.id!]"
                     placeholder="+ apelido"
                     (keyup.enter)="adicionarApelido(c)"
                     [disabled]="salvandoApelido[c.id!]">
              <button class="btn-ghost mini" (click)="adicionarApelido(c)"
                      [disabled]="salvandoApelido[c.id!] || !(novoApelido[c.id!] || '').trim()">
                {{ salvandoApelido[c.id!] ? '…' : 'add' }}
              </button>
            </div>
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

    /* editor de apelidos por clube */
    .apehint { margin: 0 0 10px; }
    .clubes-ape li.clube-li { flex-direction: column; align-items: stretch; gap: 6px; cursor: default; padding: 10px; }
    .clubes-ape li.clube-li:hover { background: var(--surface-2); }
    .clube-hd { display: flex; align-items: center; gap: 8px; }
    .clube-nome { font-weight: 700; }
    .apelinha { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
    .ape-chip { display: inline-flex; align-items: center; gap: 5px; padding: 2px 6px 2px 9px; font-size: 11px;
                background: rgba(62,166,255,.14); border: 1px solid var(--accent); border-radius: 999px; }
    .ape-x { background: transparent; border: 0; color: var(--text-dim); font-size: 14px; line-height: 1; cursor: pointer; padding: 0 2px; }
    .ape-x:hover { color: var(--loss); }
    .semape { font-style: italic; }
    .ape-input { width: 120px; padding: 3px 8px; font-size: 12px; }
    .mini { font-size: 11px; }
    .mini { padding: 2px 7px; font-size: 11px; }
    @media (max-width: 900px) { .grid3 { grid-template-columns: 1fr; } }
    .importbox { padding: 16px; margin: 16px 0; }
    .ibhd { display: flex; align-items: center; gap: 16px; }
    .ibhd .mini { margin: 4px 0 0; }
    .ibhd code { background: var(--surface-2); padding: 1px 6px; border-radius: 5px; }
    .uploadbtn { cursor: pointer; white-space: nowrap; }
    .uploadbtn.disabled { opacity: .45; cursor: not-allowed; }
    .ibres { margin-top: 12px; }
    .pill.ok { background: rgba(46,204,113,.2); color: var(--win); }
    .pill.warn { background: rgba(231,76,60,.18); color: var(--loss); }
    .avisos { margin-top: 8px; max-height: 140px; overflow: auto; }
    .alerta { margin-top: 10px; }

    .lotelist { margin-top: 10px; max-height: 220px; overflow: auto; }
    .loteitem { display: flex; gap: 6px; align-items: baseline; padding: 3px 0; }
    .loteitem .ico { color: var(--win); font-weight: 700; }
    .loteitem.err .ico { color: var(--loss); }

    .sugs { margin: 12px 0; }
    .sug { display: flex; align-items: center; gap: 10px; padding: 6px 8px; border-radius: 8px; }
    .sug:hover { background: var(--surface-2); }
    .fusaorow { display: flex; align-items: flex-end; gap: 10px; margin-top: 12px; flex-wrap: wrap; }
    .fcol { display: flex; flex-direction: column; gap: 4px; min-width: 190px; }
    .fcol select { min-width: 190px; }
    .seta { padding-bottom: 8px; color: var(--text-dim); font-size: 18px; }
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
  /** rascunho do apelido em edição, por clubeId. */
  novoApelido: { [clubeId: number]: string } = {};
  /** flag de "salvando" por clubeId (desabilita o input). */
  salvandoApelido: { [clubeId: number]: boolean } = {};
  importResult?: ImportCadastro;

  // upload global
  loteResult?: ImportLote;
  enviandoGlobal = false;

  // fusão
  duplicados?: DuplicadoSugestao[];
  manterId?: number;
  removerId?: number;
  fundindo = false;
  fusaoResult?: FusaoResult;

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

  /** Upload global: N arquivos .html; mandante vem do nome do arquivo. */
  uploadGlobal(ev: Event) {
    const input = ev.target as HTMLInputElement;
    const files = Array.from(input.files ?? []);
    input.value = '';
    if (!files.length || !this.campSel) return;

    this.enviandoGlobal = true;
    this.loteResult = undefined;
    this.partidaSvc.importarGlobal(files, this.campSel.id!).subscribe({
      next: r => {
        this.enviandoGlobal = false;
        this.loteResult = r;
        // a importação pode ter criado clubes novos
        this.selecionarCamp(this.campSel!);
      },
      error: () => {
        this.enviandoGlobal = false;
        this.loteResult = { total: files.length, importadas: 0, ignoradas: 0, comErro: files.length,
                            resultados: [], mensagem: 'Falha ao enviar os arquivos.' };
      }
    });
  }

  // ---------------- fusão ----------------

  carregarDuplicados() {
    if (!this.campSel) return;
    this.clubeSvc.duplicados(this.campSel.id!).subscribe(d => this.duplicados = d);
  }

  usarSugestao(d: DuplicadoSugestao) {
    // mantém, por padrão, o clube com mais partidas (menos trabalho de transferência)
    if (d.partidasA >= d.partidasB) { this.manterId = d.clubeAId; this.removerId = d.clubeBId; }
    else { this.manterId = d.clubeBId; this.removerId = d.clubeAId; }
  }

  podeFundir(): boolean {
    return !!this.manterId && !!this.removerId && this.manterId !== this.removerId;
  }

  fundir() {
    if (!this.podeFundir()) return;
    const manter = this.clubes.find(c => c.id === this.manterId);
    const remover = this.clubes.find(c => c.id === this.removerId);
    const ok = confirm(
      `Fundir "${remover?.nome}" em "${manter?.nome}"?\n\n` +
      `Todas as partidas de "${remover?.nome}" passarão para "${manter?.nome}", ` +
      `e "${remover?.nome}" será excluído.\n\nEsta ação não pode ser desfeita.`
    );
    if (!ok) return;

    this.fundindo = true;
    this.clubeSvc.fundir(this.manterId!, this.removerId!).subscribe({
      next: r => {
        this.fundindo = false;
        this.fusaoResult = r;
        this.manterId = undefined; this.removerId = undefined;
        this.selecionarCamp(this.campSel!);
        this.carregarDuplicados();
      },
      error: e => {
        this.fundindo = false;
        this.fusaoResult = e?.error ?? { ok: false, mensagem: 'Falha ao fundir os clubes.',
                                         partidasTransferidas: 0, partidasDescartadas: 0, avisos: [] };
      }
    });
  }

  // ---------------- cadastro básico ----------------

  carregarNacoes() { this.nacaoSvc.listar().subscribe(n => this.nacoes = n); }

  selecionarNacao(n: Nacao) {
    this.nacaoSel = n; this.campSel = undefined; this.clubes = [];
    this.duplicados = undefined; this.loteResult = undefined;
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

  // ---------------- apelidos manuais ----------------

  /** Adiciona o apelido em edição ao clube (envia a lista completa). */
  adicionarApelido(c: Clube) {
    const id = c.id!;
    const novo = (this.novoApelido[id] || '').trim();
    if (!novo) return;
    const lista = [...(c.apelidos || []), novo];
    this.salvandoApelido[id] = true;
    this.clubeSvc.definirApelidos(id, lista).subscribe({
      next: atualizado => {
        c.apelidos = atualizado.apelidos || [];
        this.novoApelido[id] = '';
        this.salvandoApelido[id] = false;
      },
      error: () => { this.salvandoApelido[id] = false; }
    });
  }

  /** Remove um apelido do clube (envia a lista sem ele). */
  removerApelido(c: Clube, apelido: string) {
    const id = c.id!;
    const lista = (c.apelidos || []).filter(a => a !== apelido);
    this.salvandoApelido[id] = true;
    this.clubeSvc.definirApelidos(id, lista).subscribe({
      next: atualizado => {
        c.apelidos = atualizado.apelidos || [];
        this.salvandoApelido[id] = false;
      },
      error: () => { this.salvandoApelido[id] = false; }
    });
  }
}