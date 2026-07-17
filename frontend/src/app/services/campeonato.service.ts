import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Campeonato } from '../models/entities.model';
import { API_BASE } from './api.config';

@Injectable({ providedIn: 'root' })
export class CampeonatoService {
  private url = `${API_BASE}/campeonatos`;
  constructor(private http: HttpClient) {}
  listar(nacaoId?: number): Observable<Campeonato[]> {
    const q = nacaoId ? `?nacaoId=${nacaoId}` : '';
    return this.http.get<Campeonato[]>(`${this.url}${q}`);
  }
  criar(nacaoId: number, c: Campeonato): Observable<Campeonato> {
    return this.http.post<Campeonato>(`${this.url}?nacaoId=${nacaoId}`, c);
  }
  atualizar(id: number, c: Campeonato): Observable<Campeonato> { return this.http.put<Campeonato>(`${this.url}/${id}`, c); }
  excluir(id: number): Observable<void> { return this.http.delete<void>(`${this.url}/${id}`); }
}
