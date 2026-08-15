/**
 * Espelha os DTOs de BacktestService / Calibracao.
 *
 * Nomes iguais aos do Java de propósito: quando o backend mudar, o compilador
 * aponta onde o front precisa acompanhar.
 */

export interface Faixa {
  de: number;
  ate: number;
  n: number;
  pMedia: number;
  frequenciaReal: number;
}

export interface CalibracaoResultado {
  n: number;
  taxaBase: number;
  brier: number;
  brierBaseline: number;
  brierSkillScore: number;
  logLoss: number;
  ece: number;
  /** ECE que um modelo perfeito produziria só por flutuação amostral. */
  eceRuido: number;
  vies: number;
  faixas: Faixa[];
}

export interface IntervaloBSS {
  inferior: number;
  mediana: number;
  superior: number;
  probabilidadePositivo: number;
}

export interface RelatorioBacktest {
  sucesso: boolean;
  mensagem: string;
  modelo: string;
  partidasTotais: number;
  partidasAvaliadas: number;
  reajustes: number;
  duracaoMs: number;
  duracaoMsPorReajuste: number;
  penalidade: number;
  rhoMedio: number;
  rhoNaBorda: number;
  resolucao: number;
  leitura: string;
  campeonatos: string[];
  porMercado: { [mercado: string]: CalibracaoResultado };
  intervalos: { [mercado: string]: IntervaloBSS };
}

export interface LinhaVarredura {
  penalidade: number;
  xi: number;
  n: number;
  bssMedio: number;
  mercadosComSkill: number;
  eceRelativoMedio: number;
  resolucao: number;
  rhoMedio: number;
  rhoNaBorda: number;
  duracaoMs: number;
}

export interface Varredura {
  sucesso: boolean;
  mensagem: string;
  linhas: LinhaVarredura[];
  resolucao: number;
  vieselecao: number;
  aviso: string;
}

/** Linha da tabela de mercados, já com o que a tela precisa mostrar. */
export interface LinhaMercado {
  codigo: string;
  res: CalibracaoResultado;
  iv?: IntervaloBSS;
  eceRelativo: number;
  veredito: string;
  /** true quando o IC95 do BSS não cruza o zero, em qualquer direção. */
  conclusivo: boolean;
  /**
   * Direção da conclusão, separada de propósito.
   *
   * A primeira versão pintava de verde tudo que era "conclusivo" — inclusive
   * mercado comprovadamente PIOR que chutar a taxa base. Verde para "seu modelo
   * perde do palpite burro" é o tipo de erro visual que faz alguém apostar.
   */
  melhorQueBase: boolean;
  piorQueBase: boolean;
}