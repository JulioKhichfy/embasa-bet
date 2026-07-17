import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Nacao } from '../models/entities.model';
import { API_BASE } from './api.config';

@Injectable({ providedIn: 'root' })
export class NacaoService {
  private url = `${API_BASE}/nacoes`;
  constructor(private http: HttpClient) {}
  listar(): Observable<Nacao[]> { return this.http.get<Nacao[]>(this.url); }
  criar(n: Nacao): Observable<Nacao> { return this.http.post<Nacao>(this.url, n); }
  atualizar(id: number, n: Nacao): Observable<Nacao> { return this.http.put<Nacao>(`${this.url}/${id}`, n); }
  excluir(id: number): Observable<void> { return this.http.delete<void>(`${this.url}/${id}`); }
}
