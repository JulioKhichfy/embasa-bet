# ⚽ Football Stats v3 — Estatísticas de Futebol (SofaScore)

Sistema full-stack para gerenciar estatísticas de partidas importadas do SofaScore.

## 🏗️ Modelo de dados (novo — reconstruído do zero)

```
NAÇÃO ──1:N──> CAMPEONATO ──1:N──> CLUBE ──┐
                                            ├──> PARTIDA (contém os 2 clubes) ──1:1──> ESTATÍSTICA
CLUBE (casa) ───────────────────────────────┘        (casa / fora)
```

- **PARTIDA** contém `clubeCasa`, `clubeFora`, placar e data.
- **ESTATÍSTICA** guarda dois mapas (`casa` e `fora`) com ~45 itens cada, sincronizados
  por um único ponto de verdade: `StatFields.java` (backend) / `sofascore.model.ts` (frontend).

## 🧩 Tecnologias

| Camada   | Stack                                            |
|----------|--------------------------------------------------|
| Backend  | Java 17 · Spring Boot 3.2 · JPA · H2 (arquivo) · Jsoup · Lombok |
| Frontend | Angular 17 (standalone components)               |

---

## 🔍 Como o parser funciona

O arquivo `sofascore.properties` (em `backend/src/main/resources`) guarda os XPaths.

- **Cabeçalho** (data, nomes dos clubes, placar): extraído diretamente pelos XPaths,
  via motor XPath do JDK sobre o DOM (com fallback pelos dois primeiros `<bdi>` para os nomes).
- **Estatísticas**: cada item aparece no HTML como um bloco com 3 `<bdi>` na ordem
  `[valor_casa, NOME_DO_ITEM, valor_fora]`. O parser localiza o item pelo nome e lê o
  `<bdi>` de cima (casa) e o de baixo (fora). Item ausente → **0.0**.
- **Itens em razão** (bolas longas, cruzamentos, dribles, duelos no chão/aéreos,
  desarmes ganhos): captura **apenas o percentual** como float.

> Validado contra o `partida.html` de exemplo: Flamengo 0×3 Palmeiras (23/05/2026),
> 44 de 45 campos extraídos corretamente.

---

## 🚀 Como executar

### Pré-requisitos
- Java 17+ (`java -version`)
- Node.js 18+ (`node -v`)
- Maven 3.8+ **ou** use o wrapper `./mvnw` incluído

### 1. Backend

```bash
cd backend
mvn spring-boot:run       # ou: ./mvnw spring-boot:run
```

Sobe em `http://localhost:8080`. O H2 grava em `backend/data/footballstats.mv.db`
(persistente entre reinícios). Console H2: `http://localhost:8080/h2-console`
(JDBC URL: `jdbc:h2:file:./data/footballstats`, user `sa`, sem senha).

### 2. Frontend

```bash
cd frontend
npm install
npm start                 # ng serve → http://localhost:4200
```

---

## 🖥️ Fluxo de uso

1. **Cadastros**: crie Nação → Campeonato → Clube manualmente, **ou** importe um
   `campeonato.txt` (uma linha por campeonato: `nação;campeonato;clube1;clube2;…`).
   A importação é idempotente — registros existentes são reutilizados, não duplicados.
   Veja `campeonato-exemplo.txt` na raiz do projeto.
2. **Dashboard**: clubes agrupados por campeonato num grid de até **8 colunas**,
   **ordenados por pontos (decrescente)** — com posição (1º, 2º, …), pontos e V/E/D.
3. **Importar partida(s)**: clique no 🌐 em um card e selecione **um ou vários**
   arquivos `partida_N.html` (ex.: `partida_1.html`, `partida_2.html`, …). O clube do
   card é tratado como **CASA**. Múltiplos arquivos são processados na ordem do número N;
   cada um é importado de forma independente (uma falha não interrompe os demais).
   Clubes ausentes são criados automaticamente; partidas duplicadas são ignoradas.
4. **Detalhe do clube**: clique no nome → últimas N partidas + média aritmética
   (filtro TODOS/CASA/FORA). **Clique numa linha da tabela** para abrir, abaixo do quadro
   de médias, o quadro **"Estatísticas da Partida"** (comparativo clube × adversário).
5. **Comparação**: marque 2 clubes no dashboard → **Comparar**. Tela **dividida em dois**:
   cada lado mostra as últimas N partidas do clube em **accordions**; dentro de cada
   accordion, cada item tem um **👁 (olho)** para incluir/excluir aquele item do cálculo
   **apenas naquela partida** (a linha escurece e é descartada). Abaixo, o quadro de
   **média aritmética** de cada lado, com **select próprio de N** partidas. Alternar o olho
   recalcula a média em tempo real.
6. **Excluir** clube/campeonato/nação remove em cascata as partidas e estatísticas
   associadas (com aviso da quantidade antes de confirmar). Isso corrige o erro de
   integridade referencial (`23503`) que ocorria ao excluir clube com partidas.
7. **Anotações**: link no menu superior. Um espaço de texto livre, salvo automaticamente
   no navegador (localStorage) enquanto você digita.
8. **Backup**: botão 💾 na barra superior baixa um dump `.sql` completo do banco.

---

## 📁 Endpoints principais

| Método | Rota | Descrição |
|--------|------|-----------|
| CRUD   | `/api/nacoes`, `/api/campeonatos`, `/api/clubes` | cadastros |
| POST   | `/api/cadastros/importar` | upload do `campeonato.txt` |
| GET    | `/api/clubes/{id}/partidas-count` | nº de partidas do clube (aviso de exclusão) |
| POST   | `/api/partidas/importar` | upload de 1 HTML (params: `campeonatoId`, `clubeCasaId?`) |
| POST   | `/api/partidas/importar-lote` | upload de N arquivos `partida_N.html` (param `arquivos`) |
| GET    | `/api/partidas/clube/{id}?filtro=&limite=` | detalhe + médias |
| GET    | `/api/partidas/comparacao?a=&b=&filtro=&limite=` | comparação |
| GET    | `/api/partidas/campos` | metadata dos campos |
| GET    | `/api/backup/dump` | dump `.sql` |

---

## ⚠️ Notas

- O backend **não foi compilado** no ambiente de geração (Maven Central indisponível lá).
  Rode `mvn compile` na sua máquina; a lógica do parser foi validada por espelho em Python
  contra o HTML real. O **frontend Angular compila sem erros** (`ng build`).
- Restaurar backup: abra o console H2 e rode `RUNSCRIPT FROM 'caminho/arquivo.sql'`.
