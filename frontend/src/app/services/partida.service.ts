import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { ImportResult, ClubeDetalhe, Comparacao, CampoMeta, Partida, ImportCadastro, ImportLote, Ranking } from '../models/entities.model';
import { API_BASE } from './api.config';

@Injectable({ providedIn: 'root' })
export class PartidaService {
  private url = `${API_BASE}/partidas`;
  constructor(private http: HttpClient) {}

  listar(): Observable<Partida[]> { return this.http.get<Partida[]>(this.url); }

  importarHtml(arquivo: File, campeonatoId: number, clubeCasaId?: number): Observable<ImportResult> {
    const fd = new FormData();
    fd.append('arquivo', arquivo);
    fd.append('campeonatoId', String(campeonatoId));
    if (clubeCasaId) fd.append('clubeCasaId', String(clubeCasaId));
    return this.http.post<ImportResult>(`${this.url}/importar`, fd);
  }

  importarLote(arquivos: File[], campeonatoId: number, clubeCasaId?: number): Observable<ImportLote> {
    const fd = new FormData();
    arquivos.forEach(a => fd.append('arquivos', a));
    fd.append('campeonatoId', String(campeonatoId));
    if (clubeCasaId) fd.append('clubeCasaId', String(clubeCasaId));
    return this.http.post<ImportLote>(`${this.url}/importar-lote`, fd);
  }

  /**
   * Upload global: N arquivos de um campeonato. O mandante de cada partida vem
   * do nome do arquivo (padrao Clube_<id>.html).
   */
  importarGlobal(arquivos: File[], campeonatoId: number): Observable<ImportLote> {
    const fd = new FormData();
    arquivos.forEach(a => fd.append('arquivos', a));
    fd.append('campeonatoId', String(campeonatoId));
    return this.http.post<ImportLote>(`${this.url}/importar-global`, fd);
  }

  detalheClube(clubeId: number, filtro: string, limite: number): Observable<ClubeDetalhe> {
    return this.http.get<ClubeDetalhe>(`${this.url}/clube/${clubeId}?filtro=${filtro}&limite=${limite}`);
  }

  comparar(a: number, b: number, filtro: string, limite: number): Observable<Comparacao> {
    return this.http.get<Comparacao>(`${this.url}/comparacao?a=${a}&b=${b}&filtro=${filtro}&limite=${limite}`);
  }

  /** Ranking de todos os clubes por quesito (quadro "Dados dos clubes"). */
  ranking(filtro: string, limite: number): Observable<Ranking> {
    return this.http.get<Ranking>(`${this.url}/ranking?filtro=${filtro}&limite=${limite}`);
  }

  campos(): Observable<CampoMeta[]> { return this.http.get<CampoMeta[]>(`${this.url}/campos`); }

  importarCadastro(arquivo: File): Observable<ImportCadastro> {
    const fd = new FormData();
    fd.append('arquivo', arquivo);
    return this.http.post<ImportCadastro>(`${API_BASE}/cadastros/importar`, fd);
  }
}