# embasa-bet

Sistema de análise estatística de futebol para identificação de valor em mercados de apostas.

Coleta histórico de partidas, estima um modelo probabilístico dos placares, cruza as
probabilidades com as odds ofertadas e aponta onde existe valor esperado positivo.

**O sistema gera o bilhete. A aposta é feita manualmente, sempre.** Não há e não haverá
automação de aposta.

---

## Estado atual

> **O motor ainda NÃO está validado. Não use as recomendações de EV para apostar dinheiro.**

O último backtest agregado (418 partidas fora da amostra, 4 campeonatos) não encontrou
skill demonstrável em nenhum dos doze mercados testados. Detalhes em
[Validação](#validação-onde-realmente-estamos).

O que já funciona de ponta a ponta: coleta, parsing, persistência, estimação, projeção,
cálculo de EV e backtest. O que falta é a única coisa que importa: evidência de que as
probabilidades são melhores que chutar a taxa base.

---

## Objetivo final

Ao final de cada dia, para os jogos de futebol masculino em cartaz:

1. Estimar a probabilidade de cada seleção em ~17 mercados.
2. Comparar com as odds da bet365, **descontada a margem da casa**.
3. Listar apenas as apostas com EV positivo acima de um piso, com stake sugerido por
   Kelly fracionário.
4. Entregar isso como um bilhete que o usuário digita manualmente na casa.

Mercados-alvo:

| Família | Mercados |
|---|---|
| Gols | Ambos marcam (partida / 1º / 2º tempo), Total de gols, Faixa de gols, Margem de vitória, Tempo com mais gols |
| Contagem | Escanteios, Cartões, Ambos recebem cartão, Total de chutes, Total de chutes ao gol, Defesas dos goleiros |
| Comparativo | Time com mais chutes / chutes ao gol / escanteios / cartões |

Hoje **apenas a família de gols está implementada** no motor. As demais têm taxonomia e
importação de odds prontas, mas não têm estimador.

---

## Arquitetura

```
  SofaScore (histórico + confrontos)        bet365 (odds)
            │                                     │
   dump HTML manual / extensão            dump HTML manual
            │                                     │
            ▼                                     ▼
    SofaScoreParser                        Bet365Parser
            │                                     │
            ▼                                     ▼
   Partida / Confronto  ◄── ClubeResolver ──►    Odd
            │                                     │
            ▼                                     │
   AjusteDixonColes ──► MatrizPlacares            │
            │                │                    │
            │                ▼                    │
            │          MercadoGols                │
            │                │                    │
            │                ▼                    ▼
            │            Projecao ─────────► ValorService (EV, Kelly)
            │                                     │
            ▼                                     ▼
     BacktestService                          bilhete
```

**Princípio central da coleta:** a extensão despeja HTML bruto, o backend parseia com Jsoup.
Isso permite reprocessar capturas antigas quando o parser melhora, sem recapturar nada — e
concentra a lógica frágil em código Java testável em vez de content script.

---

## Pipeline de dados

### SofaScore → Partida / Confronto

Página de jogo aberta no Chrome, `<main>` salvo como `<Clube>_<eventId>.html`.
O mesmo arquivo serve duas vezes: antes do jogo vira `Confronto`, depois vira `Partida`
com estatísticas.

O parser extrai:

- Placar final (âncora estrutural: o `<span>` com filhos `[dígito, "-", dígito]`)
- **Placar do intervalo**, do marcador literal `HT 1 - 0` no fluxo da partida
- Árbitro **e sua média histórica de cartões** — o preditor isolado mais forte do mercado
  de cartões, que o SofaScore entrega de graça
- Data, horário
- **IDs externos** do breadcrumb: `/football/team/botafogo/1958` → clube 1958;
  `/football/tournament/brazil/brasileirao-serie-a/325` → campeonato 325
- Estatísticas por período (`#tabpanel-ALL`, `#tabpanel-1ST`, `#tabpanel-2ND`)

**Limitação conhecida:** só o painel `ALL` vem no dump. O React do SofaScore monta apenas a
aba ativa. Para ter estatísticas por tempo, a extensão precisa clicar nas abas antes de
salvar. Nenhum dos mercados-alvo depende disso hoje (os mercados por tempo usam o placar
do intervalo, que já é extraído).

### bet365 → Odd

Página do jogo com os grupos de mercado expandidos, salva como HTML.

As classes CSS **não são ofuscadas** — `gl-MarketGroupPod`, `gl-Market_General`,
`bbl-BetBuilderParticipant_Odds` são semânticas e estáveis. O layout é coluna-a-coluna:
uma coluna de rótulos de linha, N colunas de odds com cabeçalho.

Por que parsear DOM e não texto: no mercado "Total de Gols", a coluna "Menos de" tem 6
valores para 7 linhas (não existe "menos de 0 gols"). No texto corrido o alinhamento
quebra e não há como saber qual linha faltou. **No DOM a bet365 emite uma célula vazia** e
o grid é perfeitamente retangular.

Casos que exigem cuidado, já tratados:

- Um pod pode conter **vários grids** (Margem de Vitória traz "X ou Mais" e "X exatos"
  lado a lado, com cabeçalhos repetidos)
- Pods com `TabSwitcher` (Partida / 1º Tempo / 2º Tempo) só trazem a aba ativa
- `Defesas de Goleiro` vem com as odds mas sem rótulo de seleção — o parser **recusa** em
  vez de inferir pela ordem

### Tradução de vocabulário

`MapeamentoMercado` converte a tripla crua da bet365 (título do pod, rótulo da linha,
cabeçalho da coluna) na taxonomia interna. Duas armadilhas semânticas resolvidas ali:

1. **"2 ou Mais Gols" ≠ "Mais de 2".** A casa usa piso inclusivo; a taxonomia usa piso
   exclusivo. `"2 ou Mais"` vira `linha = 1`. Copiar o número produz um erro de uma casa
   inteira, e o resultado continua plausível — ninguém percebe.
2. **O eixo do período muda de lugar.** Em "Ambos os Times Marcarem" o período está nas
   *linhas* e Sim/Não nas *colunas*; em "Margem de Vitória" está em *abas*; em "Escanteios"
   não existe. O mapeamento sempre lê as duas coordenadas juntas.

Cobertura atual: 225 de 283 cotações (80%) num dump real. As 58 restantes são mercados que
a taxonomia ainda não modela, cada uma com motivo nomeado no relatório de importação.

---

## Modelo estatístico

### Estimação

`AjusteDixonColes` — máxima verossimilhança conjunta por coordinate ascent com busca por
seção áurea. Estima simultaneamente:

- força de ataque e defesa de cada clube (2 parâmetros por clube)
- vantagem de mando
- ρ da correção Dixon-Coles para placares baixos

**Por que conjunta e não parcial:** com λ fixo, a estimativa de ρ é monótona no intervalo
válido e sempre encerra na borda. Com λ estimado junto, as forças absorvem o nível de gols
e sobra para ρ apenas o que ele de fato carrega.

**Identificabilidade:** ataque e defesa só são determinados a menos de uma constante. O
ataque é centralizado em zero a cada iteração para fixar a escala.

**Regularização ridge** sobre as forças em escala log, com penalidade em função da razão
observações/parâmetro. Necessária porque uma temporada de ~190 jogos com 20 clubes dá menos
de 5 observações por parâmetro — o MLE não distingue "ataque forte" de "quatro jogos de
sorte" e produz forças extremas que descrevem o passado sem prever nada.

### Derivação dos mercados

`MercadoGols` trata ambos marcam, total de gols, faixa e margem como **quatro perguntas à
mesma matriz conjunta**, não quatro modelos. Cada uma é a soma das células (i,j) que
satisfazem um predicado.

A consequência prática mais valiosa: **múltiplas correlacionadas do mesmo jogo saem certas**.
Com λ 1,55 × 1,10:

```
P(mais de 2 gols)      49.4%
P(ambos marcam)        53.2%
produto (ERRADO)       26.3%   → odd justa 3.81
conjunta real (CERTO)  40.5%   → odd justa 2.47
```

54% de subestimação. Casas cobram caro em bet builder porque a maioria dos apostadores
multiplica as probabilidades.

### Valor

`ValorService`. O passo que quase todo mundo pula: **descontar a margem da casa antes de
comparar**. `1/odd` não é a probabilidade que a casa atribui ao evento — é a probabilidade
mais o lucro dela. Num mercado Sim/Não com 1.95 / 1.80 as implícitas somam 106,8%.

```
implícitas cruas    Sim 51.3%   Não 55.6%   soma 106.8%
justas              Sim 48.0%   Não 52.0%   soma 100.0%
modelo              Sim 53.2%
  contra a crua     +1.9 pp
  contra a justa    +5.2 pp     ← a vantagem real é quase 3x maior
```

Método proporcional. **Limitação conhecida:** as casas carregam mais margem no azarão
(favourite-longshot bias), então o proporcional superestima a probabilidade justa de odds
altas. Para apostar sistematicamente acima de ~4.00, trocar por Shin.

Stake por Kelly fracionário (¼ por padrão) — Kelly integral assume que `p` está certo, e
o nosso `p` vem de um modelo com erro.

---

## Validação: onde realmente estamos

`BacktestService` faz walk-forward estrito: cada partida é prevista usando **apenas** as
partidas anteriores a ela. Ajustar no conjunto inteiro e testar nele dá resultado excelente
e falso — em teste sintético a diferença de Brier foi 0,012, exatamente o tamanho do
autoengano.

### Último resultado (418 partidas, 4 campeonatos)

```
mercado            BSS   P(BSS>0)  ECE/ruído
btts            -0.005       34%      1.11x
over_0          -0.035        4%      1.52x
over_1          +0.011       66%      0.94x
over_2          -0.002       44%      1.42x
over_3          -0.026       12%      1.59x
over_4          +0.014       68%      1.43x
faixa_1_2       +0.010       68%      0.98x
faixa_1_3       -0.014       11%      1.00x
margem_2mais    -0.004       36%      0.92x
margem_3mais    +0.015       74%      0.79x
vitoria_casa    -0.007       35%      1.81x
empate          -0.016        9%      1.12x

resolução do teste: ±0.042 de BSS
```

**Leitura:** nenhum mercado é distinguível da taxa base. Quatro positivos, oito negativos,
nenhum significativo.

**Mas houve progresso real.** Com um único campeonato (n=126) os doze eram negativos, de
−0,02 a −0,18. Com agregação e regularização, a magnitude caiu para a casa do zero e quatro
viraram positivos. A patologia de superdispersão que produzia excesso de 0-0 e de goleadas
foi em boa parte corrigida.

**A calibração agora está boa.** ECE mediana de 1,12× o ruído amostral — dentro do
esperado para um modelo correto. O que falta não é calibração, é **discriminação**: o
modelo acerta a taxa média mas ainda não separa bem os jogos.

Isso importa muito para apostas. Um modelo calibrado mas sem discriminação só enxerga
"valor" onde o preço da casa se afasta da taxa base — e como a casa *discrimina* bem, você
estaria sistematicamente apostando contra informação correta.

### Sinais de alerta em aberto

- **ρ médio +0,015 e 7 dos 43 ajustes terminaram na borda.** Em futebol ρ deveria ficar
  ligeiramente negativo. ρ encostado no limite não é estimativa, é pedido de socorro: o
  modelo está usando ρ para compensar algo que ρ não descreve — provavelmente dispersão
  residual nas forças.
- **Os extremos erram nos dois sentidos.** Em `over_3`, previsão de 7,7% deu 18,8% real;
  previsão de 45% deu 20%. Falta encolhimento.
- **A resolução (±0,042) ainda é do tamanho do efeito procurado** (skill realista em gols
  fica entre 0,02 e 0,06). Precisamos de mais dados para distinguir.

---

## Como rodar

```bash
# backend
cd backend && mvn spring-boot:run     # http://localhost:8080

# frontend
cd frontend && npm install && npm start
```

H2 em arquivo (`./data/footballstats`), `ddl-auto=update` — as tabelas são criadas
automaticamente. Console em `/h2-console`.

> **Atenção:** `spring.servlet.multipart.max-file-size` precisa de pelo menos 60MB.
> Um dump da bet365 tem ~18MB e o limite padrão de 20MB estoura sem aviso claro.

### Endpoints principais

```
POST /api/partidas/importar-lote          dumps do SofaScore → Partida
POST /api/confrontos/importar-lote        dumps do SofaScore → Confronto
GET  /api/confrontos?data=2026-08-14      jogos do dia

POST /api/odds/importar-lote              dumps da bet365 → Odd
GET  /api/odds/confronto/{id}             cotações mais recentes
GET  /api/odds/confronto/{id}/historico   série temporal (movimento de linha)

POST /api/projecoes/confronto/{id}?modelo=DIXON_COLES
GET  /api/projecoes/confronto/{id}/valor?evMinimo=0.05&fracaoKelly=0.25
POST /api/projecoes/invalidar/{campeonatoId}

POST /api/backtest/campeonato/{id}?aquecimento=100&passoReajuste=10
POST /api/backtest/agregado?campeonatos=34,100,101,104
```

---

## Estrutura

```
backend/src/main/java/com/footballstats/
  model/          Nacao, Campeonato, Clube, Partida, Estatistica,
                  Confronto, Odd, Projecao, Mercados, RegraCartoes, StatFields
  parser/         SofaScoreParser, Bet365Parser
  probabilidade/  MatrizPlacares, MercadoGols, AjusteDixonColes, Calibracao
  service/        ClubeResolver, ClubeNomes, ConfrontoImportService, OddImportService,
                  MapeamentoMercado, ProjecaoService, ValorService, BacktestService
  repository/     um por entidade
  controller/     um por área

frontend/src/app/
  components/     dashboard, comparacao, clube-detalhe, cadastros, apostas, anotacoes
  models/         probabilidade.model.ts (motor espelhado), entities, sofascore, aposta
  services/       clientes HTTP
```

O motor probabilístico existe em **duas implementações**: TypeScript (frontend, para
exploração interativa) e Java (backend, para persistir projeções). A duplicação é
deliberada — sem gravar a projeção antes do jogo não existe backtest honesto — e é
protegida por comparação numérica: as duas foram rodadas sobre 1296 valores com erro
relativo máximo de 5,2e-16, e sobre o estimador completo com erro **zero**.

---

## Decisões de projeto e o porquê

**Ambiguidade falha alto, não adivinha.** Nome de clube ambíguo cria um clube novo e deixa
a fusão para a tela manual; odd sem rótulo de seleção não é gravada; placar de intervalo
que não passa nas guardas é descartado. O custo de um duplicado é dois cliques; o de uma
fusão errada é histórico misturado e irreversível.

**Confronto é separado de Partida.** Partida é fato consumado com placar; Confronto é
expectativa com hora, árbitro e odds. Na mesma tabela metade das colunas seria nula e o
motor não saberia o que pode entrar na amostra de treino.

**Odd não tem unique key.** Odds se movem, e o movimento de linha é sinal. Cada captura é
um fato novo com carimbo de tempo. `findAtuaisByConfronto` recorta o preço atual para a
tela; o histórico fica.

**O texto cru da bet365 é guardado** (`origemMercado`, `origemLinha`, `origemSelecao`).
Permite remapear capturas antigas quando o mapeamento melhorar.

**Projeções são persistidas.** Recalcular depois do jogo usaria dados que incluem o próprio
jogo. Sem gravar o que o modelo disse *antes*, não há como medir calibração.

**O ajuste é cacheado por campeonato**, não por confronto — as forças são do campeonato
inteiro, e refazer por jogo multiplicaria ~400ms pelo número de jogos do dia sem mudar nada.

**IDs externos têm precedência sobre nomes.** O texto muda (patrocínio, acento, sufixo de
estado); `/football/team/botafogo/1958` não muda.

**Laços de importação em lote ficam no controller**, não no serviço, para que o proxy
transacional do Spring valha por arquivo: um dump corrompido faz rollback só dele.

---

## Roadmap

**Antes de qualquer coisa**

- [ ] Mais dados. A resolução do backtest precisa cair para ~±0,02 para enxergar skill.
  Mais campeonatos e mais temporadas.
- [ ] Varredura da penalidade de regularização fora da amostra (agora que n=418 torna a
  comparação minimamente significativa). Não calibrar contra amostra pequena.
- [ ] Investigar ρ na borda: dispersão residual ou má especificação?
- [ ] Decaimento temporal (ξ do Dixon-Coles original): força de clube muda ao longo da
  temporada e hoje o jogo da rodada 1 pesa igual ao da rodada 20.

**Depois de BSS positivo demonstrado**

- [ ] Estimadores das famílias que faltam: Binomial Negativa para escanteios e cartões
  (superdispersos), Binomial condicional para defesas do goleiro
  (`defesas ≈ Binomial(chutes ao gol do adversário, p)`), convolução para os
  comparativos
- [ ] Tela de backtest com curva de confiabilidade
- [ ] Tela do bilhete
- [ ] Extensão `bet365-scan`

---

## Avisos

**Termos de uso.** Automação sobre conta logada viola os termos da bet365, com risco real
de fechamento de conta e retenção de saldo. O desenho atual reduz a superfície — leitura do
DOM de uma aba aberta manualmente, sem navegação nem chamadas à API deles — mas não elimina
o risco. A ingestão de odds é plugável: uma API legítima de odds substitui o dump sem
alterar o motor.

**Isto não é aconselhamento financeiro.** Aposta esportiva tem valor esperado negativo por
construção — a margem da casa existe justamente para isso. Este projeto tenta encontrar as
exceções, e ainda não demonstrou que consegue.

**Não aposte com base neste sistema enquanto o backtest não mostrar BSS positivo com
intervalo de confiança acima de zero.** Um modelo mal calibrado não perde devagar: ele
aponta exatamente para as apostas erradas, porque o "valor" que enxerga é o próprio erro.