/**
 * SofaScoreModel.ts
 * Espelho do backend (StatFields.java). Fonte de verdade da metadata de campos
 * para renderizar a tela de comparacao e detalhe sem hardcode nos templates.
 *
 * O backend tambem expoe esta lista em GET /api/partidas/campos; este arquivo
 * serve de fallback e para tipagem.
 */
export type TipoStat = 'VALOR' | 'PERCENT' | 'KM' | 'RATIO';

export interface StatMeta {
  campo: string;
  rotulo: string;
  categoria: string;
  tipo: TipoStat;
}

export const CATEGORIAS = [
  'Visão Geral', 'Finalizações', 'Ataque', 'Passes', 'Duelos', 'Defendendo', 'Goleiro'
];

export const STAT_FIELDS: StatMeta[] = [
  // Visão Geral
  { campo: 'posseDeBola',            rotulo: 'Posse de bola',            categoria: 'Visão Geral', tipo: 'PERCENT' },
  { campo: 'golsEsperados',          rotulo: 'Gols esperados (xG)',      categoria: 'Visão Geral', tipo: 'VALOR' },
  { campo: 'distanciaPercorrida',    rotulo: 'Distância percorrida',     categoria: 'Visão Geral', tipo: 'KM' },
  { campo: 'grandesChances',         rotulo: 'Grandes chances',          categoria: 'Visão Geral', tipo: 'VALOR' },
  { campo: 'finalizacoes',           rotulo: 'Finalizações',             categoria: 'Visão Geral', tipo: 'VALOR' },
  { campo: 'defesasDoGoleiro',       rotulo: 'Defesas do goleiro',       categoria: 'Visão Geral', tipo: 'VALOR' },
  { campo: 'numeroDeSprints',        rotulo: 'Número de sprints',        categoria: 'Visão Geral', tipo: 'VALOR' },
  { campo: 'escanteios',             rotulo: 'Escanteios',               categoria: 'Visão Geral', tipo: 'VALOR' },
  { campo: 'faltas',                 rotulo: 'Faltas',                   categoria: 'Visão Geral', tipo: 'VALOR' },
  { campo: 'passes',                 rotulo: 'Passes',                   categoria: 'Visão Geral', tipo: 'VALOR' },
  { campo: 'desarmes',               rotulo: 'Desarmes',                 categoria: 'Visão Geral', tipo: 'VALOR' },
  { campo: 'cartoesAmarelos',        rotulo: 'Cartões amarelos',         categoria: 'Visão Geral', tipo: 'VALOR' },
  { campo: 'cartoesVermelhos',       rotulo: 'Cartões vermelhos',        categoria: 'Visão Geral', tipo: 'VALOR' },
  // Finalizações
  { campo: 'finalizacoesNoGol',      rotulo: 'Finalizações no gol',      categoria: 'Finalizações', tipo: 'VALOR' },
  { campo: 'finalizacoesNaTrave',    rotulo: 'Finalizações na trave',    categoria: 'Finalizações', tipo: 'VALOR' },
  { campo: 'finalizacoesParaFora',   rotulo: 'Finalizações para fora',   categoria: 'Finalizações', tipo: 'VALOR' },
  { campo: 'chutesDefendidos',       rotulo: 'Chutes defendidos',        categoria: 'Finalizações', tipo: 'VALOR' },
  { campo: 'finalizacoesDentroArea', rotulo: 'Finalizações de dentro da área', categoria: 'Finalizações', tipo: 'VALOR' },
  { campo: 'finalizacoesForaArea',   rotulo: 'Finalizações de fora da área',   categoria: 'Finalizações', tipo: 'VALOR' },
  // Ataque
  { campo: 'grandesChancesMarcadas', rotulo: 'Grandes chances marcadas', categoria: 'Ataque', tipo: 'VALOR' },
  { campo: 'grandesChancesPerdidas', rotulo: 'Grandes chances perdidas', categoria: 'Ataque', tipo: 'VALOR' },
  { campo: 'passeEmProfundidade',    rotulo: 'Passe em profundidade',    categoria: 'Ataque', tipo: 'VALOR' },
  { campo: 'acoesBolaAreaAdversaria',rotulo: 'Ações com a bola na área adversária', categoria: 'Ataque', tipo: 'VALOR' },
  { campo: 'faltasSofridasTercoFinal',rotulo: 'Faltas sofridas no terço final',    categoria: 'Ataque', tipo: 'VALOR' },
  { campo: 'impedimentos',           rotulo: 'Impedimentos',             categoria: 'Ataque', tipo: 'VALOR' },
  // Passes
  { campo: 'passesCertos',           rotulo: 'Passes certos',            categoria: 'Passes', tipo: 'VALOR' },
  { campo: 'laterais',               rotulo: 'Laterais',                 categoria: 'Passes', tipo: 'VALOR' },
  { campo: 'entradasTercoFinal',     rotulo: 'Entradas no terço final',  categoria: 'Passes', tipo: 'VALOR' },
  { campo: 'passesTercoFinal',       rotulo: 'Passes no terço final',    categoria: 'Passes', tipo: 'VALOR' },
  { campo: 'bolasLongas',            rotulo: 'Bolas longas (%)',         categoria: 'Passes', tipo: 'RATIO' },
  { campo: 'cruzamentos',            rotulo: 'Cruzamentos (%)',          categoria: 'Passes', tipo: 'RATIO' },
  // Duelos
  { campo: 'duelos',                 rotulo: 'Duelos (%)',               categoria: 'Duelos', tipo: 'PERCENT' },
  { campo: 'perdasDeBola',           rotulo: 'Perdas de bola',           categoria: 'Duelos', tipo: 'VALOR' },
  { campo: 'duelosNoChao',           rotulo: 'Duelos no chão (%)',       categoria: 'Duelos', tipo: 'RATIO' },
  { campo: 'duelosAereos',           rotulo: 'Duelos aéreos (%)',        categoria: 'Duelos', tipo: 'RATIO' },
  { campo: 'dribles',                rotulo: 'Dribles (%)',              categoria: 'Duelos', tipo: 'RATIO' },
  // Defendendo
  { campo: 'desarmesGanhos',         rotulo: 'Desarmes ganhos (%)',      categoria: 'Defendendo', tipo: 'RATIO' },
  { campo: 'totalDeDesarmes',        rotulo: 'Total de desarmes',        categoria: 'Defendendo', tipo: 'VALOR' },
  { campo: 'interceptacoes',         rotulo: 'Interceptações',           categoria: 'Defendendo', tipo: 'VALOR' },
  { campo: 'recuperacoesDeBola',     rotulo: 'Recuperações de bola',     categoria: 'Defendendo', tipo: 'VALOR' },
  { campo: 'cortes',                 rotulo: 'Cortes',                   categoria: 'Defendendo', tipo: 'VALOR' },
  { campo: 'errosLevaramFinalizacao',rotulo: 'Erros que levaram à finalização', categoria: 'Defendendo', tipo: 'VALOR' },
  // Goleiro
  { campo: 'golsEvitados',           rotulo: 'Gols evitados',            categoria: 'Goleiro', tipo: 'VALOR' },
  { campo: 'grandesDefesas',         rotulo: 'Grandes defesas',          categoria: 'Goleiro', tipo: 'VALOR' },
  { campo: 'socos',                  rotulo: 'Socos',                    categoria: 'Goleiro', tipo: 'VALOR' },
  { campo: 'tirosDeMeta',            rotulo: 'Tiros de meta',            categoria: 'Goleiro', tipo: 'VALOR' },
];
