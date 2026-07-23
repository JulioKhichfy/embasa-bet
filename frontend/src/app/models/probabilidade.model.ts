/**
 * probabilidade.model.ts
 *
 * Modelos probabilísticos para estimar o resultado de uma partida a partir
 * dos gols esperados (lambda) de cada lado.
 *
 * Todos os modelos produzem uma MATRIZ de placares P(i,j) e, a partir dela,
 * derivam 1X2, BTTS, chance dupla e placar mais provável. Assim a troca de
 * modelo afeta só a construção da matriz — o resto do cálculo é comum.
 *
 * Referências conceituais:
 *  - Poisson simples: assume independência entre os gols dos dois times.
 *  - Dixon & Coles (1997): Poisson + correção tau nos placares baixos,
 *    corrigindo a subestimação de 0-0 / 1-1 típica do Poisson puro.
 *  - Bivariate Poisson: introduz covariância (lambda3) entre os times,
 *    modelando jogos "abertos" (ambos marcam) vs "travados".
 *  - Negative Binomial: cauda mais pesada que Poisson (overdispersion),
 *    representando melhor goleadas e a variância real do futebol.
 */

export type ModeloId = 'poisson' | 'dixoncoles' | 'bivariate' | 'negbin';

export interface ModeloInfo {
  id: ModeloId;
  nome: string;
  descricao: string;
}

export const MODELOS: ModeloInfo[] = [
  {
    id: 'poisson',
    nome: 'Poisson simples',
    descricao: 'Baseline clássico. Assume independência entre os gols dos dois clubes. Rápido, mas subestima empates.'
  },
  {
    id: 'dixoncoles',
    nome: 'Dixon-Coles',
    descricao: 'Poisson com correção (τ) nos placares baixos (0-0, 1-0, 0-1, 1-1). Padrão da literatura para futebol.'
  },
  {
    id: 'bivariate',
    nome: 'Bivariate Poisson',
    descricao: 'Adiciona covariância entre os clubes: modela jogos abertos vs travados. Aumenta empates de forma natural.'
  },
  {
    id: 'negbin',
    nome: 'Negative Binomial',
    descricao: 'Cauda mais pesada (overdispersion). Representa melhor a variância real e goleadas.'
  }
];

/** Resultado agregado de qualquer modelo. */
export interface ResultadoModelo {
  vCasa: number; empate: number; vFora: number;
  bttsSim: number; bttsNao: number;
  dc1X: number; dc12: number; dcX2: number;
  placarCasa: number; placarFora: number;
  golsEsperados: number;         // total esperado (soma dos lambdas)
  over: { [linha: string]: number };  // P(total > linha), ex.: "1.5" -> 72.3
}

const MAXG = 10;

// ---------------------------------------------------------------------------
// Distribuições base
// ---------------------------------------------------------------------------

/** ln(k!) via soma direta (k pequeno aqui, precisão suficiente). */
function lnFat(k: number): number {
  let s = 0;
  for (let i = 2; i <= k; i++) s += Math.log(i);
  return s;
}

/** P(X = k) para Poisson(lambda). */
export function poisson(k: number, lambda: number): number {
  if (lambda <= 0) return k === 0 ? 1 : 0;
  return Math.exp(-lambda + k * Math.log(lambda) - lnFat(k));
}

/** ln(Gamma(x)) — aproximação de Lanczos (para Negative Binomial). */
function lnGamma(x: number): number {
  const g = 7;
  const c = [
    0.99999999999980993, 676.5203681218851, -1259.1392167224028,
    771.32342877765313, -176.61502916214059, 12.507343278686905,
    -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7
  ];
  if (x < 0.5) return Math.log(Math.PI / Math.sin(Math.PI * x)) - lnGamma(1 - x);
  x -= 1;
  let a = c[0];
  const t = x + g + 0.5;
  for (let i = 1; i < g + 2; i++) a += c[i] / (x + i);
  return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
}

/**
 * P(X = k) para Negative Binomial com média lambda e parâmetro de dispersão r.
 * r → ∞ converge para Poisson; r menor = mais variância (cauda pesada).
 */
export function negBin(k: number, lambda: number, r: number): number {
  if (lambda <= 0) return k === 0 ? 1 : 0;
  const p = r / (r + lambda);
  return Math.exp(
    lnGamma(k + r) - lnGamma(r) - lnFat(k) + r * Math.log(p) + k * Math.log(1 - p)
  );
}

// ---------------------------------------------------------------------------
// Construção da matriz de placares por modelo
// ---------------------------------------------------------------------------

/** Matriz Poisson simples: produto das marginais (independência). */
function matrizPoisson(lc: number, lf: number): number[][] {
  const m: number[][] = [];
  for (let i = 0; i <= MAXG; i++) {
    m[i] = [];
    for (let j = 0; j <= MAXG; j++) m[i][j] = poisson(i, lc) * poisson(j, lf);
  }
  return m;
}

/**
 * Correção tau de Dixon-Coles, aplicada só aos placares baixos.
 * rho < 0 aumenta 0-0 e 1-1 e reduz 1-0 / 0-1 (padrão empírico no futebol).
 */
function tauDixonColes(i: number, j: number, lc: number, lf: number, rho: number): number {
  if (i === 0 && j === 0) return 1 - lc * lf * rho;
  if (i === 0 && j === 1) return 1 + lc * rho;
  if (i === 1 && j === 0) return 1 + lf * rho;
  if (i === 1 && j === 1) return 1 - rho;
  return 1;
}

/** Matriz Dixon-Coles: Poisson corrigido nos placares baixos. */
function matrizDixonColes(lc: number, lf: number, rho: number): number[][] {
  const m = matrizPoisson(lc, lf);
  for (let i = 0; i <= 1; i++) {
    for (let j = 0; j <= 1; j++) {
      m[i][j] *= Math.max(0.0001, tauDixonColes(i, j, lc, lf, rho));
    }
  }
  return m;
}

/**
 * Bivariate Poisson: X = X1 + X3, Y = X2 + X3, com X3 ~ Poisson(l3) comum.
 * P(x,y) = sum_{k=0}^{min(x,y)} Pois(x-k,l1) Pois(y-k,l2) Pois(k,l3)
 * l3 > 0 gera correlação positiva (jogos abertos/travados).
 */
function matrizBivariate(lc: number, lf: number, l3: number): number[][] {
  // l1 e l2 descontam a parte comum para preservar as médias marginais
  const l1 = Math.max(0.01, lc - l3);
  const l2 = Math.max(0.01, lf - l3);
  const m: number[][] = [];
  for (let i = 0; i <= MAXG; i++) {
    m[i] = [];
    for (let j = 0; j <= MAXG; j++) {
      let s = 0;
      const kmax = Math.min(i, j);
      for (let k = 0; k <= kmax; k++) {
        s += poisson(i - k, l1) * poisson(j - k, l2) * poisson(k, l3);
      }
      m[i][j] = s;
    }
  }
  return m;
}

/** Matriz Negative Binomial (marginais independentes, cauda pesada). */
function matrizNegBin(lc: number, lf: number, r: number): number[][] {
  const m: number[][] = [];
  for (let i = 0; i <= MAXG; i++) {
    m[i] = [];
    for (let j = 0; j <= MAXG; j++) m[i][j] = negBin(i, lc, r) * negBin(j, lf, r);
  }
  return m;
}

// ---------------------------------------------------------------------------
// API pública
// ---------------------------------------------------------------------------

export interface ParamsModelo {
  /** Dixon-Coles: correção dos placares baixos (tipicamente -0.03 a -0.15). */
  rho?: number;
  /** Bivariate: covariância comum entre os times. */
  lambda3?: number;
  /** Negative Binomial: dispersão (menor = cauda mais pesada). */
  r?: number;
}

export const PARAMS_PADRAO: Required<ParamsModelo> = {
  rho: -0.05,
  lambda3: 0.15,
  r: 8
};

// ---------------------------------------------------------------------------
// Estimacao do rho (Dixon-Coles) por maxima verossimilhanca
// ---------------------------------------------------------------------------
//
// No artigo original, rho e estimado JUNTO com as forcas de ataque/defesa de
// cada time (MLE conjunta). Esta funcao faz a versao parcial: dado o lambda de
// cada lado (das medias) FIXO, acha o rho que maximiza a verossimilhanca das
// celulas baixas de Dixon-Coles:
//     L(rho) = sum_k  ln( tau(x_k, y_k; lc_k, lf_k, rho) )
// (tau = 1 fora de {0-0,0-1,1-0,1-1}, entao so esses placares informam rho.)
//
// ATENCAO / LIMITACAO IMPORTANTE:
//   Com lambda FIXO, essa verossimilhanca e MONOTONA em rho no intervalo valido
//   (mais empates -> rho cada vez mais negativo, sem otimo interior). Ou seja,
//   esta estimativa tende a bater na BORDA (RHO_MIN ou onde tau>0 permite) e NAO
//   substitui a calibracao completa. Um rho com otimo interior "de verdade" so
//   aparece quando lambda e estimado CONJUNTAMENTE (ver estimarRhoConjunto mais
//   abaixo, que ajusta forcas de ataque/defesa e rho ao mesmo tempo).
//
//   Use estimarRho apenas como diagnostico ("os dados pedem rho mais negativo
//   ou mais proximo de zero?"), nao como valor final para producao.
//
// Implementacao: busca em grade + refinamento aureo, deterministica e sem
// dependencias externas.

/** Uma partida observada para calibrar rho. */
export interface PartidaObservada {
  golsCasa: number;   // x
  golsFora: number;   // y
  lambdaCasa: number; // gols esperados do mandante (das medias)
  lambdaFora: number; // gols esperados do visitante
}

export interface EstimativaRho {
  rho: number;              // rho estimado (recortado ao intervalo valido)
  logVerossimilhanca: number;
  partidasUsadas: number;   // quantas partidas caem nas 4 celulas baixas
  convergiu: boolean;       // false se nao ha informacao (usa o padrao)
}

/** Intervalo empirico admissivel para rho no futebol. */
const RHO_MIN = -0.30;
const RHO_MAX = 0.10;

/** ln(tau) de uma unica partida; -Infinity se tau <= 0 (rho invalido). */
function lnTau(x: number, y: number, lc: number, lf: number, rho: number): number {
  const t = tauDixonColes(x, y, lc, lf, rho);
  return t > 0 ? Math.log(t) : -Infinity;
}

/** Soma de ln(tau) sobre as partidas (log-verossimilhanca a menos de constante). */
function logVeross(parts: PartidaObservada[], rho: number): number {
  let s = 0;
  for (const p of parts) {
    const l = lnTau(p.golsCasa, p.golsFora, p.lambdaCasa, p.lambdaFora, rho);
    if (l === -Infinity) return -Infinity;
    s += l;
  }
  return s;
}

/**
 * Estima rho por maxima verossimilhanca a partir das partidas observadas.
 * Se nenhuma partida cair nas celulas baixas (sem informacao), devolve o padrao
 * com convergiu=false.
 */
export function estimarRho(partidas: PartidaObservada[]): EstimativaRho {
  // So as 4 celulas baixas informam rho.
  const relevantes = partidas.filter(p =>
    (p.golsCasa === 0 || p.golsCasa === 1) && (p.golsFora === 0 || p.golsFora === 1));

  if (relevantes.length === 0) {
    return { rho: PARAMS_PADRAO.rho, logVerossimilhanca: 0, partidasUsadas: 0, convergiu: false };
  }

  // 1) busca em grade grossa para achar a regiao do maximo (evitando bordas invalidas)
  const N = 240;
  let melhorRho = PARAMS_PADRAO.rho;
  let melhorL = -Infinity;
  for (let i = 0; i <= N; i++) {
    const rho = RHO_MIN + (RHO_MAX - RHO_MIN) * (i / N);
    const l = logVeross(relevantes, rho);
    if (l > melhorL) { melhorL = l; melhorRho = rho; }
  }

  // 2) refinamento por bisseccao aurea em torno do melhor ponto da grade
  const passo = (RHO_MAX - RHO_MIN) / N;
  let a = Math.max(RHO_MIN, melhorRho - passo);
  let b = Math.min(RHO_MAX, melhorRho + passo);
  const gr = (Math.sqrt(5) - 1) / 2;
  for (let it = 0; it < 60 && (b - a) > 1e-6; it++) {
    const c = b - gr * (b - a);
    const d = a + gr * (b - a);
    if (logVeross(relevantes, c) < logVeross(relevantes, d)) a = c; else b = d;
  }
  const rhoRef = (a + b) / 2;
  const lRef = logVeross(relevantes, rhoRef);

  const rhoFinal = lRef >= melhorL ? rhoRef : melhorRho;
  return {
    rho: Math.min(RHO_MAX, Math.max(RHO_MIN, rhoFinal)),
    logVerossimilhanca: Math.max(lRef, melhorL),
    partidasUsadas: relevantes.length,
    convergiu: true
  };
}

// ---------------------------------------------------------------------------
// Estimacao CONJUNTA Dixon-Coles: forcas de ataque/defesa + mando + rho
// ---------------------------------------------------------------------------
//
// Esta e a calibracao "de verdade", como no artigo (versao estatica, sem
// decaimento temporal). Modelo para uma partida i (mandante) x j (visitante):
//
//     lambda = exp(ataque[i] + defesa[j] + mando)     // gols do mandante
//     mu     = exp(ataque[j] + defesa[i])             // gols do visitante
//
// e a probabilidade do placar (x,y) segue Dixon-Coles:
//     P(x,y) = tau(x,y; lambda, mu, rho) * Pois(x; lambda) * Pois(y; mu)
//
// 'ataque' alto = time faz mais gols; 'defesa' alto = time SOFRE mais gols
// (defesa fraca). 'mando' > 0 = vantagem de jogar em casa. Restricao de
// identificabilidade: media(ataque) = 0.
//
// Ajuste: subida por coordenadas (coordinate ascent) com busca 1-D em cada
// parametro. Sem dependencias externas; deterministico. Nao e o mais rapido,
// mas o volume de dados aqui (um campeonato) e pequeno.

/** Partida bruta para a estimacao conjunta (indices de time 0..n-1). */
export interface PartidaBruta {
  casa: number;   // indice do mandante
  fora: number;   // indice do visitante
  golsCasa: number;
  golsFora: number;
}

export interface ForcasClube {
  indice: number;
  ataque: number;   // >0 marca mais
  defesa: number;   // >0 sofre mais (defesa pior)
}

export interface AjusteDixonColes {
  forcas: ForcasClube[];
  mando: number;               // vantagem de mando (log-escala)
  rho: number;                 // correcao DC estimada
  logVerossimilhanca: number;
  iteracoes: number;
  convergiu: boolean;
  /** gols esperados de um confronto i(casa) x j(fora), ja com mando e forcas. */
}

/** ln P(x,y) sob Dixon-Coles para uma partida (usado na verossimilhanca). */
function lnProbDC(x: number, y: number, lambda: number, mu: number, rho: number): number {
  const t = tauDixonColes(x, y, lambda, mu, rho);
  if (t <= 0) return -Infinity;
  // ln Pois(x;lambda) + ln Pois(y;mu) + ln tau
  const lnPoisX = -lambda + x * Math.log(lambda) - lnFat(x);
  const lnPoisY = -mu + y * Math.log(mu) - lnFat(y);
  return lnPoisX + lnPoisY + Math.log(t);
}

/** Log-verossimilhanca total do modelo dado o vetor de parametros. */
function logLLConjunta(parts: PartidaBruta[], ataque: number[], defesa: number[],
                       mando: number, rho: number): number {
  let s = 0;
  for (const p of parts) {
    const lambda = Math.exp(ataque[p.casa] + defesa[p.fora] + mando);
    const mu = Math.exp(ataque[p.fora] + defesa[p.casa]);
    const l = lnProbDC(p.golsCasa, p.golsFora, lambda, mu, rho);
    if (l === -Infinity) return -Infinity;
    s += l;
  }
  return s;
}

/** Busca 1-D (secao aurea) do valor de x em [a,b] que maximiza f. */
function maximiza1D(f: (x: number) => number, a: number, b: number, iter = 40): number {
  const gr = (Math.sqrt(5) - 1) / 2;
  let c = b - gr * (b - a);
  let d = a + gr * (b - a);
  let fc = f(c), fd = f(d);
  for (let i = 0; i < iter && (b - a) > 1e-6; i++) {
    if (fc < fd) { a = c; c = d; fc = fd; d = a + gr * (b - a); fd = f(d); }
    else         { b = d; d = c; fd = fc; c = b - gr * (b - a); fc = f(c); }
  }
  return (a + b) / 2;
}

/**
 * Estima forcas de ataque/defesa, mando e rho por MLE conjunta (Dixon-Coles).
 *
 * @param nTimes  numero de clubes (indices 0..nTimes-1 usados nas partidas)
 * @param partidas confrontos observados
 * @param maxIter  iteracoes de coordinate ascent (default 60)
 */
export function estimarDixonColes(nTimes: number, partidas: PartidaBruta[],
                                  maxIter = 60): AjusteDixonColes {
  const ataque = new Array(nTimes).fill(0);
  const defesa = new Array(nTimes).fill(0);
  let mando = 0.1;
  let rho = PARAMS_PADRAO.rho;

  if (partidas.length === 0 || nTimes === 0) {
    return { forcas: [], mando, rho, logVerossimilhanca: 0, iteracoes: 0, convergiu: false };
  }

  const centralizarAtaque = () => {
    const m = ataque.reduce((s, v) => s + v, 0) / nTimes;
    for (let i = 0; i < nTimes; i++) ataque[i] -= m;   // media(ataque)=0
  };

  let llAnt = logLLConjunta(partidas, ataque, defesa, mando, rho);
  let it = 0, convergiu = false;
  for (; it < maxIter; it++) {
    // ataque e defesa de cada time
    for (let i = 0; i < nTimes; i++) {
      ataque[i] = maximiza1D(v => {
        const bak = ataque[i]; ataque[i] = v;
        const l = logLLConjunta(partidas, ataque, defesa, mando, rho);
        ataque[i] = bak; return l;
      }, -3, 3);
      defesa[i] = maximiza1D(v => {
        const bak = defesa[i]; defesa[i] = v;
        const l = logLLConjunta(partidas, ataque, defesa, mando, rho);
        defesa[i] = bak; return l;
      }, -3, 3);
    }
    centralizarAtaque();
    // mando
    mando = maximiza1D(v => logLLConjunta(partidas, ataque, defesa, v, rho), -1, 1);
    // rho (agora COM otimo interior, porque lambda/mu se ajustam junto)
    rho = maximiza1D(v => logLLConjunta(partidas, ataque, defesa, mando, v), -0.30, 0.10);

    const ll = logLLConjunta(partidas, ataque, defesa, mando, rho);
    if (Math.abs(ll - llAnt) < 1e-5) { convergiu = true; llAnt = ll; it++; break; }
    llAnt = ll;
  }

  const forcas: ForcasClube[] = [];
  for (let i = 0; i < nTimes; i++) forcas.push({ indice: i, ataque: ataque[i], defesa: defesa[i] });
  return { forcas, mando, rho, logVerossimilhanca: llAnt, iteracoes: it, convergiu };
}

/** Gols esperados de i(casa) x j(fora) a partir de um ajuste conjunto. */
export function golsEsperadosAjuste(aj: AjusteDixonColes, i: number, j: number): { casa: number; fora: number } {
  const fi = aj.forcas[i], fj = aj.forcas[j];
  if (!fi || !fj) return { casa: 0, fora: 0 };
  return {
    casa: Math.exp(fi.ataque + fj.defesa + aj.mando),
    fora: Math.exp(fj.ataque + fi.defesa)
  };
}

/** Monta a matriz de placares conforme o modelo escolhido. */
export function matrizPlacares(modelo: ModeloId, lc: number, lf: number,
                               params: ParamsModelo = {}): number[][] {
  const p = { ...PARAMS_PADRAO, ...params };
  switch (modelo) {
    case 'dixoncoles': return matrizDixonColes(lc, lf, p.rho);
    case 'bivariate':  return matrizBivariate(lc, lf, Math.min(p.lambda3, Math.min(lc, lf) * 0.9));
    case 'negbin':     return matrizNegBin(lc, lf, p.r);
    default:           return matrizPoisson(lc, lf);
  }
}

/**
 * Calcula todas as probabilidades a partir da matriz do modelo escolhido.
 * A matriz é normalizada (soma 1) antes de derivar os mercados, garantindo
 * que as correções (tau, truncamento em MAXG) não distorçam os totais.
 */
export function calcular(modelo: ModeloId, lc: number, lf: number,
                         params: ParamsModelo = {}): ResultadoModelo {
  const m = matrizPlacares(modelo, lc, lf, params);

  // normalização
  let soma = 0;
  for (let i = 0; i <= MAXG; i++) for (let j = 0; j <= MAXG; j++) soma += m[i][j];
  if (soma <= 0) soma = 1;

  let vCasa = 0, empate = 0, vFora = 0, btts = 0;
  let melhor = -1, pi = 0, pj = 0;
  const overLinhas = [0.5, 1.5, 2.5, 3.5, 4.5];
  const overAcc: { [k: string]: number } = {};
  overLinhas.forEach(l => overAcc[String(l)] = 0);

  for (let i = 0; i <= MAXG; i++) {
    for (let j = 0; j <= MAXG; j++) {
      const p = m[i][j] / soma;
      if (i > j) vCasa += p; else if (i === j) empate += p; else vFora += p;
      if (i >= 1 && j >= 1) btts += p;
      if (p > melhor) { melhor = p; pi = i; pj = j; }
      const tot = i + j;
      overLinhas.forEach(l => { if (tot > l) overAcc[String(l)] += p; });
    }
  }

  const pct = (x: number) => x * 100;
  const over: { [k: string]: number } = {};
  overLinhas.forEach(l => over[String(l)] = pct(overAcc[String(l)]));

  return {
    vCasa: pct(vCasa), empate: pct(empate), vFora: pct(vFora),
    bttsSim: pct(btts), bttsNao: pct(1 - btts),
    dc1X: pct(vCasa + empate),
    dc12: pct(vCasa + vFora),
    dcX2: pct(empate + vFora),
    placarCasa: pi, placarFora: pj,
    golsEsperados: lc + lf,
    over
  };
}