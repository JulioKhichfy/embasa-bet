export interface Nacao { id?: number; nome: string; }

export interface Campeonato { id?: number; nome: string; nacao?: Nacao; }

export interface Clube { id?: number; nome: string; campeonato?: Campeonato; }

export interface Estatistica {
  id?: number;
  casa: { [campo: string]: number };
  fora: { [campo: string]: number };
}

export interface Partida {
  id?: number;
  data: string;
  clubeCasa: Clube;
  clubeFora: Clube;
  golsCasa: number;
  golsFora: number;
  estatistica?: Estatistica;
}

export interface ImportResult {
  importada: boolean;
  mensagem: string;
  nomeArquivo?: string;
  partidaId?: number;
  clubeCasa?: string;
  clubeFora?: string;
  golsCasa?: number;
  golsFora?: number;
  data?: string;
}

export interface ImportLote {
  total: number;
  importadas: number;
  ignoradas: number;
  comErro: number;
  resultados: ImportResult[];
  mensagem: string;
}

export interface PartidaResumo {
  partidaId: number;
  data: string;
  adversario: string;
  emCasa: boolean;
  golsFeitos: number;
  golsSofridos: number;
  resultado: 'V' | 'E' | 'D';
  pontos: number;
  estatisticas?: { [campo: string]: number };
  estatisticasAdversario?: { [campo: string]: number };
}

export interface ImportCadastro {
  nacoesCriadas: number;
  campeonatosCriados: number;
  clubesCriados: number;
  linhasProcessadas: number;
  linhasIgnoradas: number;
  avisos: string[];
  mensagem: string;
}

export interface ClubeDetalhe {
  clubeId: number;
  clubeNome: string;
  filtro: string;
  totalPartidas: number;
  partidas: PartidaResumo[];
  medias: { [campo: string]: number };
  mediaPontos: number;
  mediaGolsFeitos: number;
  mediaGolsSofridos: number;
}

export interface CampoMeta {
  campo: string;
  rotulo: string;
  categoria: string;
  tipo: string; // VALOR | PERCENT | KM | RATIO
}

export interface Comparacao {
  clubeAId: number;
  clubeANome: string;
  clubeBId: number;
  clubeBNome: string;
  filtro: string;
  nPartidas: number;
  mediasA: { [campo: string]: number };
  mediasB: { [campo: string]: number };
  campos: CampoMeta[];
}

export interface FusaoResult {
  ok: boolean;
  mensagem: string;
  clubeMantidoId?: number;
  clubeMantidoNome?: string;
  clubeRemovidoId?: number;
  clubeRemovidoNome?: string;
  partidasTransferidas: number;
  partidasDescartadas: number;
  avisos: string[];
}

export interface DuplicadoSugestao {
  clubeAId: number;
  clubeANome: string;
  partidasA: number;
  clubeBId: number;
  clubeBNome: string;
  partidasB: number;
  campeonatoNome: string;
}

export interface RankingItem {
  clubeId: number;
  clubeNome: string;
  campeonatoNome: string;
  jogos: number;
  total: number;
  media: number;
}

export interface RankingQuesito {
  chave: string;
  rotulo: string;
  itens: RankingItem[];
}

export interface Ranking {
  filtro: string;
  limite: number;
  quesitos: RankingQuesito[];
}