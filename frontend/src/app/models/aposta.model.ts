/**
 * aposta.model.ts
 *
 * Cálculo de cobertura (hedge) entre resultados mutuamente exclusivos
 * (Vitória / Empate / Derrota).
 *
 * ===================== A MATEMÁTICA, SEM ILUSÃO =====================
 *
 * Odd decimal O paga retorno = stake * O (já incluso o stake).
 *
 * A probabilidade IMPLÍCITA de uma odd é 1/O. A soma dos inversos de todas
 * as odds de um mercado é o "book":
 *
 *      book = 1/O1 + 1/OX + 1/O2
 *
 *  - book < 1  -> ARBITRAGEM: existe distribuição de stakes com LUCRO
 *                 garantido em qualquer cenário. É raro e some rápido.
 *  - book = 1  -> jogo justo: dá para travar exatamente o valor atual.
 *  - book > 1  -> MARGEM DA CASA (o caso normal). NÃO existe distribuição
 *                 de stakes que garanta lucro. Toda cobertura apenas
 *                 REDISTRIBUI o resultado; o valor esperado permanece
 *                 negativo. "Zerar o prejuízo sempre" é impossível aqui.
 *
 * A margem da casa é (book - 1) / book.
 *
 * Por isso este módulo nunca devolve um "stake mágico que zera": ele devolve
 * o resultado REAL de cada cenário e sinaliza explicitamente quando o
 * objetivo de zerar é inatingível.
 */

/** Uma aposta já feita (posição aberta). */
export interface Aposta {
  id: number;
  /** rótulo do cenário: 'V' | 'E' | 'D' */
  cenario: Cenario;
  odd: number;
  stake: number;
  /** momento/observação (ex.: "pré-jogo", "aos 30'") */
  nota?: string;
}

export type Cenario = 'V' | 'E' | 'D';
export const CENARIOS: Cenario[] = ['V', 'E', 'D'];

export const NOME_CENARIO: Record<Cenario, string> = {
  V: 'Vitória',
  E: 'Empate',
  D: 'Derrota'
};

/** Resultado financeiro em um cenário específico. */
export interface ResultadoCenario {
  cenario: Cenario;
  /** total retornado pelas apostas vencedoras neste cenário */
  retorno: number;
  /** total investido em todas as apostas */
  investido: number;
  /** retorno - investido (negativo = prejuízo) */
  lucro: number;
}

/** Análise completa de uma carteira de apostas. */
export interface AnaliseCarteira {
  investido: number;
  cenarios: ResultadoCenario[];
  /** pior lucro entre os cenários */
  piorCaso: number;
  /** melhor lucro entre os cenários */
  melhorCaso: number;
  /** true se TODOS os cenários têm lucro >= 0 (posição travada no verde) */
  garantido: boolean;
}

/** Soma dos inversos das odds atuais (book). */
export function book(odds: Record<Cenario, number>): number {
  return CENARIOS.reduce((s, c) => s + (odds[c] > 1 ? 1 / odds[c] : 0), 0);
}

/** Margem da casa em % (0 se book <= 1). */
export function margemCasa(odds: Record<Cenario, number>): number {
  const b = book(odds);
  if (b <= 0) return 0;
  return b > 1 ? ((b - 1) / b) * 100 : 0;
}

/** Há arbitragem (lucro garantido possível) se o book < 1. */
export function temArbitragem(odds: Record<Cenario, number>): boolean {
  const b = book(odds);
  return b > 0 && b < 1;
}

/** Analisa a carteira: quanto sobra/falta em cada cenário. */
export function analisar(apostas: Aposta[]): AnaliseCarteira {
  const investido = apostas.reduce((s, a) => s + (a.stake || 0), 0);

  const cenarios: ResultadoCenario[] = CENARIOS.map(c => {
    const retorno = apostas
      .filter(a => a.cenario === c)
      .reduce((s, a) => s + (a.stake || 0) * (a.odd || 0), 0);
    return { cenario: c, retorno, investido, lucro: retorno - investido };
  });

  const lucros = cenarios.map(c => c.lucro);
  return {
    investido,
    cenarios,
    piorCaso: Math.min(...lucros),
    melhorCaso: Math.max(...lucros),
    garantido: lucros.every(l => l >= 0)
  };
}

/** Diagnóstico de uma sugestão de cobertura. */
export type TipoSugestao =
  | 'ARBITRAGEM'        // lucro garantido em todos os cenários
  | 'TRAVA_PREJUIZO'    // iguala os cenários, mas o resultado é negativo
  | 'IMPOSSIVEL_ZERAR'  // não existe stake que zere o prejuízo
  | 'SEM_POSICAO';      // nada apostado ainda

export interface Sugestao {
  tipo: TipoSugestao;
  /** cenário a cobrir */
  cenario: Cenario;
  /** stake sugerido para IGUALAR os retornos (hedge clássico) */
  stakeEquilibrio: number;
  /** lucro resultante se apostar stakeEquilibrio (igual em todos os cenários cobertos) */
  lucroEquilibrio: number;
  /** stake que seria necessário para o lucro ficar exatamente 0 (ou null se impossível) */
  stakeZerar: number | null;
  /** exposição total após aplicar o stake de equilíbrio */
  exposicaoTotal: number;
  /** explicação honesta do que está acontecendo */
  mensagem: string;
}

/**
 * Calcula a cobertura para um cenário-alvo, dadas as apostas existentes
 * e a odd atual (ao vivo) desse cenário.
 *
 * Lógica: apostar S no cenário C com odd O gera retorno S*O se C ocorrer.
 * O lucro no cenário C passa a ser:  retornoC + S*O - (investido + S)
 * O lucro nos demais cenários vira:  retornoOutro - (investido + S)
 *
 * O stake de equilíbrio iguala o lucro de C ao PIOR dos outros cenários.
 * Se o resultado desse equilíbrio for negativo, a "trava" é de prejuízo —
 * e este módulo diz isso com todas as letras.
 */
export function sugerirCobertura(
  apostas: Aposta[],
  cenarioAlvo: Cenario,
  oddAtual: number
): Sugestao {
  const base = analisar(apostas);

  if (base.investido <= 0) {
    return {
      tipo: 'SEM_POSICAO', cenario: cenarioAlvo,
      stakeEquilibrio: 0, lucroEquilibrio: 0, stakeZerar: null,
      exposicaoTotal: 0,
      mensagem: 'Nenhuma aposta registrada ainda. Adicione uma posição para calcular cobertura.'
    };
  }
  if (!oddAtual || oddAtual <= 1) {
    return {
      tipo: 'IMPOSSIVEL_ZERAR', cenario: cenarioAlvo,
      stakeEquilibrio: 0, lucroEquilibrio: base.piorCaso, stakeZerar: null,
      exposicaoTotal: base.investido,
      mensagem: 'Informe uma odd válida (maior que 1,00) para o cenário a cobrir.'
    };
  }

  const retornoAlvo = base.cenarios.find(c => c.cenario === cenarioAlvo)!.retorno;
  const outros = base.cenarios.filter(c => c.cenario !== cenarioAlvo);
  const piorRetornoOutros = Math.min(...outros.map(c => c.retorno));

  /*
   * Stake de equilíbrio: queremos MAXIMIZAR o pior cenário.
   * Ao apostar S no alvo com odd O:
   *   lucro(alvo)   = retornoAlvo + S*O - (investido + S)   -> cresce com S
   *   lucro(outros) = piorRetornoOutros - (investido + S)    -> decresce com S
   * O ótimo do pior caso está onde as duas retas se cruzam:
   *   retornoAlvo + S*O = piorRetornoOutros
   *   S = (piorRetornoOutros - retornoAlvo) / O
   * Se o alvo já paga mais que o pior dos outros, S=0 já é o ótimo
   * (apostar mais só pioraria o pior caso).
   */
  const stakeEquilibrio = Math.max(0, (piorRetornoOutros - retornoAlvo) / oddAtual);
  const investidoFinal = base.investido + stakeEquilibrio;
  // no equilíbrio ambos os lados valem o mesmo; se S=0, o pior caso é o original
  const lucroEquilibrio = stakeEquilibrio > 0
    ? piorRetornoOutros - investidoFinal
    : Math.min(retornoAlvo, piorRetornoOutros) - base.investido;

  // Stake para lucro ZERO no cenário alvo: retornoAlvo + S*O = investido + S
  //   S*(O - 1) = investido - retornoAlvo   ->   S = (investido - retornoAlvo) / (O - 1)
  const numerador = base.investido - retornoAlvo;
  let stakeZerar: number | null = null;
  if (numerador <= 0) {
    stakeZerar = 0; // já está no zero ou acima neste cenário
  } else {
    const s = numerador / (oddAtual - 1);
    stakeZerar = isFinite(s) && s > 0 ? s : null;
  }

  // Zerar o cenário alvo pode DESTRUIR os outros cenários: verificar.
  let zerarViavel = false;
  if (stakeZerar !== null) {
    const invTeste = base.investido + stakeZerar;
    const piorOutrosComStake = piorRetornoOutros - invTeste;
    zerarViavel = piorOutrosComStake >= -0.005; // tolerância de centavos
  }

  let tipo: TipoSugestao;
  let mensagem: string;

  if (lucroEquilibrio >= 0.005) {
    tipo = 'ARBITRAGEM';
    mensagem = `Posição travada no lucro: apostando ${fmt(stakeEquilibrio)} em ${NOME_CENARIO[cenarioAlvo]} `
      + `você garante ${fmt(lucroEquilibrio)} independente do resultado. Isso só é possível porque as odds `
      + `atuais abrem arbitragem — é raro, confirme os valores antes de apostar.`;
  } else if (zerarViavel) {
    tipo = 'TRAVA_PREJUIZO';
    mensagem = `Apostando ${fmt(stakeZerar!)} em ${NOME_CENARIO[cenarioAlvo]} o prejuízo desse cenário vai a zero, `
      + `mas isso NÃO elimina o risco: se sair outro resultado, a perda aumenta porque o stake extra também é gasto.`;
  } else {
    tipo = 'IMPOSSIVEL_ZERAR';
    mensagem = `Não existe stake que zere o prejuízo aqui. Com estas odds, cobrir ${NOME_CENARIO[cenarioAlvo]} `
      + `apenas redistribui a perda: o melhor que se consegue é travar ${fmt(lucroEquilibrio)} `
      + `em todos os cenários. Apostar mais aumenta a exposição, não a segurança.`;
  }

  return {
    tipo,
    cenario: cenarioAlvo,
    stakeEquilibrio,
    lucroEquilibrio,
    stakeZerar: zerarViavel ? stakeZerar : null,
    exposicaoTotal: investidoFinal,
    mensagem
  };
}

/** Simula o resultado da carteira caso a sugestão seja aplicada. */
export function simular(apostas: Aposta[], cenarioAlvo: Cenario, odd: number, stake: number): AnaliseCarteira {
  const virtual: Aposta[] = [
    ...apostas,
    { id: -1, cenario: cenarioAlvo, odd, stake, nota: 'simulação' }
  ];
  return analisar(virtual);
}

/** Plano de cobertura completa: stake em cada cenário faltante. */
export interface PlanoCompleto {
  /** stake sugerido por cenário (0 = não apostar) */
  stakes: Record<Cenario, number>;
  /** lucro final garantido em QUALQUER cenário (negativo = prejuízo travado) */
  lucroGarantido: number;
  /** exposição total após aplicar o plano */
  exposicaoTotal: number;
  /** true se lucroGarantido >= 0 */
  garantido: boolean;
  /** book das odds atuais */
  book: number;
  mensagem: string;
}

/**
 * Cobertura COMPLETA: distribui stakes entre os cenários ainda descobertos
 * de modo que o retorno seja igual em todos — o hedge de verdade.
 *
 * Resolve: para um alvo de retorno R igual em todos os cenários,
 *   stake_c = (R - retornoAtual_c) / odd_c   (para cada cenário c)
 *   investidoFinal = investido + soma(stake_c)
 *   lucro = R - investidoFinal
 *
 * Substituindo, o lucro é máximo quando R satisfaz:
 *   R = (investido + soma(R/odd_c - retornoAtual_c/odd_c))  =>  resolve-se para R.
 *
 * Se book >= 1, o lucro resultante é SEMPRE <= 0: a margem da casa impede
 * lucro garantido. A função devolve o valor real, sem maquiagem.
 */
export function coberturaCompleta(
  apostas: Aposta[],
  oddsAtuais: Record<Cenario, number>
): PlanoCompleto {
  const base = analisar(apostas);
  const b = book(oddsAtuais);

  const validos = CENARIOS.filter(c => oddsAtuais[c] > 1);
  const stakes: Record<Cenario, number> = { V: 0, E: 0, D: 0 };

  if (validos.length === 0) {
    return {
      stakes, lucroGarantido: base.piorCaso, exposicaoTotal: base.investido,
      garantido: false, book: b,
      mensagem: 'Informe odds válidas (> 1,00) para calcular a cobertura.'
    };
  }

  // R (retorno alvo igual em todos) tal que lucro seja maximizado:
  //   lucro(R) = R - investido - soma_c[(R - retAtual_c)/odd_c]
  //            = R(1 - soma_c 1/odd_c) - investido + soma_c(retAtual_c/odd_c)
  // Se (1 - book) > 0 -> lucro cresce com R (arbitragem, ilimitado na teoria).
  // Se (1 - book) < 0 -> lucro decresce com R: o melhor R é o MENOR viável,
  //   ou seja, o que não exige stake negativo: R = max_c(retAtual_c).
  const retAtual: Record<Cenario, number> = { V: 0, E: 0, D: 0 };
  CENARIOS.forEach(c => {
    retAtual[c] = base.cenarios.find(x => x.cenario === c)!.retorno;
  });

  const R = Math.max(...CENARIOS.map(c => retAtual[c]));

  let somaStakes = 0;
  validos.forEach(c => {
    const s = Math.max(0, (R - retAtual[c]) / oddsAtuais[c]);
    stakes[c] = s;
    somaStakes += s;
  });

  const exposicaoTotal = base.investido + somaStakes;
  const lucroGarantido = R - exposicaoTotal;
  const garantido = lucroGarantido >= -0.005;

  let mensagem: string;
  if (b < 1) {
    mensagem = `As odds atuais abrem ARBITRAGEM (book ${b.toFixed(4)} < 1). `
      + `Distribuindo os stakes abaixo você trava ${fmt(lucroGarantido)} em qualquer resultado. `
      + `Confirme os valores na casa antes de apostar — janelas assim fecham em segundos.`;
  } else if (garantido) {
    mensagem = `Posição travada: ${fmt(lucroGarantido)} garantido em qualquer resultado.`;
  } else {
    mensagem = `Com book ${b.toFixed(4)} (margem de ${margemCasa(oddsAtuais).toFixed(2)}% a favor da casa), `
      + `NÃO existe combinação de stakes que zere o prejuízo. O melhor resultado possível é travar `
      + `${fmt(lucroGarantido)} em todos os cenários — ou seja, aceitar essa perda de forma controlada. `
      + `Apostar além disso aumenta a exposição e o valor esperado continua negativo.`;
  }

  return { stakes, lucroGarantido, exposicaoTotal, garantido, book: b, mensagem };
}

function fmt(v: number): string {
  return v.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}