import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { RelatorioBacktest, Varredura } from '../models/backtest.model';
import { API_BASE } from './api.config';

@Injectable({ providedIn: 'root' })
export class BacktestService {
  private url = `${API_BASE}/backtest`;

  constructor(private http: HttpClient) {}

  /**
   * Backtest agregado. Cada campeonato é ajustado separadamente e a avaliação
   * é somada — é assim que se sai de 90 casos sem esperar temporadas.
   */
  agregado(campeonatoIds: number[], modelo: string,
           aquecimento: number, passoReajuste: number): Observable<RelatorioBacktest> {
    const q = `campeonatos=${campeonatoIds.join(',')}&modelo=${modelo}`
            + `&aquecimento=${aquecimento}&passoReajuste=${passoReajuste}`;
    return this.http.post<RelatorioBacktest>(`${this.url}/agregado?${q}`, {});
  }

  varredura(campeonatoIds: number[], penalidades: string, decaimentos: string,
            modelo: string, aquecimento: number, passoReajuste: number): Observable<Varredura> {
    const q = `campeonatos=${campeonatoIds.join(',')}&penalidades=${penalidades}`
            + `&decaimentos=${decaimentos}&modelo=${modelo}`
            + `&aquecimento=${aquecimento}&passoReajuste=${passoReajuste}`;
    return this.http.post<Varredura>(`${this.url}/varredura?${q}`, {});
  }
}