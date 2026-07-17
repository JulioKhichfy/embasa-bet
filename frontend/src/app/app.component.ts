import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterOutlet, RouterLink, RouterLinkActive } from '@angular/router';
import { BackupService } from './services/backup.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <div class="topbar">
      <span class="brand">⚽ FOOTBALL STATS</span>
      <nav>
        <a routerLink="/dashboard" routerLinkActive="active">Dashboard</a>
        <a routerLink="/cadastros" routerLinkActive="active">Cadastros</a>
        <a routerLink="/anotacoes" routerLinkActive="active">Anotações</a>
        <a routerLink="/apostas" routerLinkActive="active">Apostas</a>
      </nav>
      <span class="spacer"></span>

      <button class="btn-ghost" (click)="backup.dump()" title="Baixar dump .sql do banco">
        💾 Backup
      </button>

      <label class="btn-ghost uploadbtn" title="Restaurar um dump .sql (substitui os dados atuais)">
        📥 Importar dump
        <input type="file" accept=".sql" hidden (change)="onRestaurar($event)">
      </label>

      <button class="btn-danger" (click)="abrirLimpar()" title="Apagar o conteúdo das tabelas">
        🗑 Apagar banco
      </button>
    </div>

    <!-- feedback -->
    <div *ngIf="msg" class="toast" [class.err]="!msgOk" (click)="msg=''">
      {{ msg }}
    </div>

    <!-- modal de confirmação do wipe -->
    <div *ngIf="mostrarLimpar" class="overlay" (click)="fecharLimpar()">
      <div class="modal card" (click)="$event.stopPropagation()">
        <h2>⚠ Apagar o conteúdo do banco</h2>
        <p>
          Esta ação esvazia <b>todas</b> as tabelas: nações, campeonatos, clubes, partidas e estatísticas.
          <b>Não há como desfazer.</b>
        </p>
        <p class="muted mini">
          A estrutura das tabelas é mantida e os ids recomeçam do 1 — não é preciso reiniciar o backend.
        </p>
        <p class="muted mini">
          Recomendado: gere um 💾 Backup antes de continuar.
        </p>
        <p>Para confirmar, digite <code>APAGAR</code> abaixo:</p>
        <input [(ngModel)]="textoConfirma" placeholder="APAGAR" (keyup.enter)="confirmarLimpar()" autocomplete="off">
        <div class="mBtns">
          <button class="btn-ghost" (click)="fecharLimpar()">Cancelar</button>
          <button class="btn-danger" [disabled]="textoConfirma !== 'APAGAR' || processando"
                  (click)="confirmarLimpar()">
            {{ processando ? 'Apagando…' : 'Apagar definitivamente' }}
          </button>
        </div>
      </div>
    </div>

    <div class="container">
      <router-outlet></router-outlet>
    </div>
  `,
  styles: [`
    .uploadbtn { cursor: pointer; display: inline-flex; align-items: center; padding: 8px 14px;
                 border-radius: 8px; font-size: 13px; font-weight: 600; border: 1px solid var(--border); }
    .uploadbtn:hover { filter: brightness(1.2); }
    .toast { position: fixed; top: 66px; right: 18px; z-index: 40; max-width: 380px;
             background: rgba(46,204,113,.16); border: 1px solid var(--win); color: var(--text);
             padding: 11px 14px; border-radius: 10px; cursor: pointer; font-size: 13px; }
    .toast.err { background: rgba(231,76,60,.16); border-color: var(--loss); }
    .overlay { position: fixed; inset: 0; background: rgba(0,0,0,.6); display: grid; place-items: center; z-index: 50; }
    .modal { padding: 22px; max-width: 460px; width: calc(100% - 40px); }
    .modal h2 { margin-top: 0; color: var(--loss); }
    .modal input { width: 100%; margin: 6px 0 4px; }
    .modal code { background: var(--surface-2); padding: 1px 6px; border-radius: 5px; font-weight: 700; }
    .mini { font-size: 12px; }
    .mBtns { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
  `]
})
export class AppComponent {
  mostrarLimpar = false;
  textoConfirma = '';
  processando = false;
  msg = '';
  msgOk = true;

  constructor(public backup: BackupService) {}

  private toast(texto: string, ok: boolean) {
    this.msg = texto; this.msgOk = ok;
    setTimeout(() => this.msg = '', 7000);
  }

  onRestaurar(ev: Event) {
    const input = ev.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;

    const ok = confirm(
      `Restaurar "${file.name}"?\n\n` +
      `Todos os dados atuais serão SUBSTITUÍDOS pelo conteúdo do arquivo. ` +
      `Esta ação não pode ser desfeita.`
    );
    if (!ok) return;

    this.backup.restaurar(file).subscribe({
      next: r => {
        this.toast(r.mensagem, r.ok);
        if (r.ok) setTimeout(() => window.location.reload(), 1200);
      },
      error: e => this.toast(e?.error?.mensagem || 'Falha ao restaurar o dump.', false)
    });
  }

  abrirLimpar() { this.mostrarLimpar = true; this.textoConfirma = ''; }
  fecharLimpar() { this.mostrarLimpar = false; this.textoConfirma = ''; }

  confirmarLimpar() {
    if (this.textoConfirma !== 'APAGAR') return;
    this.processando = true;
    this.backup.limpar(this.textoConfirma).subscribe({
      next: r => {
        this.processando = false;
        this.fecharLimpar();
        this.toast(r.mensagem, r.ok);
        if (r.ok) setTimeout(() => window.location.reload(), 1500);
      },
      error: e => {
        this.processando = false;
        this.toast(e?.error?.mensagem || 'Falha ao apagar o banco.', false);
      }
    });
  }
}