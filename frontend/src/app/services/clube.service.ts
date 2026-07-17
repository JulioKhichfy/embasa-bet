import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Clube, FusaoResult, DuplicadoSugestao } from '../models/entities.model';
import { API_BASE } from './api.config';

@Injectable({ providedIn: 'root' })
export class ClubeService {
  private url = `${API_BASE}/clubes`;
  constructor(private http: HttpClient) {}
  listar(campeonatoId?: number): Observable<Clube[]> {
    const q = campeonatoId ? `?campeonatoId=${campeonatoId}` : '';
    return this.http.get<Clube[]>(`${this.url}${q}`);
  }
  criar(campeonatoId: number, c: Clube): Observable<Clube> {
    return this.http.post<Clube>(`${this.url}?campeonatoId=${campeonatoId}`, c);
  }
  atualizar(id: number, c: Clube): Observable<Clube> { return this.http.put<Clube>(`${this.url}/${id}`, c); }
  excluir(id: number): Observable<void> { return this.http.delete<void>(`${this.url}/${id}`); }
  contarPartidas(id: number): Observable<number> { return this.http.get<number>(`${this.url}/${id}/partidas-count`); }

  /** Pares de clubes suspeitos de serem o mesmo. */
  duplicados(campeonatoId?: number): Observable<DuplicadoSugestao[]> {
    const q = campeonatoId ? `?campeonatoId=${campeonatoId}` : '';
    return this.http.get<DuplicadoSugestao[]>(`${this.url}/duplicados${q}`);
  }

  /** Funde 'removerId' dentro de 'manterId'. Resta apenas o clube mantido. */
  fundir(manterId: number, removerId: number): Observable<FusaoResult> {
    return this.http.post<FusaoResult>(`${this.url}/fundir?manterId=${manterId}&removerId=${removerId}`, {});
  }
}
