import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { API_BASE } from './api.config';

export interface BackupResp { ok: boolean; mensagem: string; }

@Injectable({ providedIn: 'root' })
export class BackupService {
  constructor(private http: HttpClient) {}

  dump(): void {
    this.http.get(`${API_BASE}/backup/dump`, { responseType: 'blob', observe: 'response' })
      .subscribe(resp => {
        const blob = resp.body as Blob;
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        const cd = resp.headers.get('Content-Disposition') || '';
        const m = cd.match(/filename="?([^"]+)"?/);
        a.download = m ? m[1] : 'footballstats_backup.sql';
        a.click();
        window.URL.revokeObjectURL(url);
      });
  }

  /** Restaura um dump .sql (substitui todo o conteúdo atual). */
  restaurar(arquivo: File): Observable<BackupResp> {
    const fd = new FormData();
    fd.append('arquivo', arquivo);
    return this.http.post<BackupResp>(`${API_BASE}/backup/restaurar`, fd);
  }

  /** Apaga TODOS os dados. Exige a palavra APAGAR como confirmação. */
  limpar(confirmacao: string): Observable<BackupResp> {
    return this.http.post<BackupResp>(
      `${API_BASE}/backup/limpar?confirmacao=${encodeURIComponent(confirmacao)}`, {});
  }
}