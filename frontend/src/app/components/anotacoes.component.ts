import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-anotacoes',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
  <div class="head">
    <h1>Anotações</h1>
    <span class="spacer"></span>
    <span class="status" *ngIf="salvoEm">salvo {{ salvoEm }}</span>
    <button class="btn-ghost" (click)="limpar()">Limpar</button>
  </div>
  <p class="muted">Espaço livre para anotações. O texto é salvo automaticamente neste navegador.</p>

  <div class="card">
    <textarea [(ngModel)]="texto" (ngModelChange)="onChange()"
              placeholder="Escreva suas anotações aqui…" spellcheck="false"></textarea>
  </div>
  <p class="muted mini">{{ texto.length }} caractere(s)</p>
  `,
  styles: [`
    .head { display: flex; align-items: center; gap: 12px; }
    .status { color: var(--win); font-size: 12px; }
    .card { padding: 4px; margin-top: 8px; }
    textarea {
      width: 100%; min-height: 60vh; resize: vertical;
      background: transparent; color: var(--text); border: none;
      font-family: 'Segoe UI', system-ui, sans-serif; font-size: 15px; line-height: 1.6;
      padding: 14px;
    }
    textarea:focus { outline: none; }
    .mini { font-size: 12px; margin-top: 6px; }
  `]
})
export class AnotacoesComponent implements OnInit {
  private readonly KEY = 'footballstats.anotacoes';
  texto = '';
  salvoEm = '';
  private debounce?: any;

  ngOnInit() {
    this.texto = localStorage.getItem(this.KEY) ?? '';
  }

  onChange() {
    clearTimeout(this.debounce);
    this.debounce = setTimeout(() => {
      localStorage.setItem(this.KEY, this.texto);
      this.salvoEm = new Date().toLocaleTimeString('pt-BR');
    }, 400);
  }

  limpar() {
    if (!confirm('Apagar todas as anotações?')) return;
    this.texto = '';
    localStorage.removeItem(this.KEY);
    this.salvoEm = '';
  }
}
