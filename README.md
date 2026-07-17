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

# APOIO GIT

Para um projeto com esta estrutura:

```text
embasa-bet/
├── .gitignore
├── backend/        (Java)
│   ├── pom.xml
│   ├── src/
│   └── target/
└── frontend/       (Angular)
    ├── src/
    ├── node_modules/
    ├── dist/
    └── .angular/
```

---

# Como remover o node_modules que já foi adicionado

O `.gitignore` **não remove arquivos já indexados**.

Como você já executou:

```bash
git add .
```

faça:

```bash
git rm -r --cached frontend/node_modules
```

Se preferir limpar tudo o que está no índice:

```bash
git rm -r --cached .
git add .
```

Assim o Git irá obedecer ao `.gitignore`.

Depois confira:

```bash
git status
```

Você deverá ver apenas os arquivos realmente necessários.

---

# Primeiro commit

```bash
git commit -m "Projeto inicial"
```

---

# Enviar para o GitHub

Como o repositório é novo:

```bash
git push -u origin master
```

A opção `-u` faz com que a branch local passe a rastrear a branch remota.

Depois basta:

```bash
git push
```

---

# Criando a branch develop

Você comentou que deseja trabalhar com duas branches.

Essa é exatamente uma das formas mais comuns de organização.

```
master
   ▲
   │
   └──── develop
```

A `master` contém somente versões prontas.

A `develop` recebe o desenvolvimento diário.

Crie-a:

```bash
git checkout -b develop
```

ou

```bash
git switch -c develop
```

Envie para o GitHub:

```bash
git push -u origin develop
```

Agora você terá:

```
origin/master
origin/develop
```

---

# Fluxo recomendado

### 1) Começar uma funcionalidade

```bash
git switch develop
git pull
```

Implemente a funcionalidade.

---

### 2) Salvar

```bash
git add .
git commit -m "Implementa autenticação JWT"
```

---

### 3) Enviar

```bash
git push
```

---

### 4) Quando tudo estiver testado

Ir para master:

```bash
git switch master
```

Atualizar:

```bash
git pull
```

Mesclar:

```bash
git merge develop
```

Enviar:

```bash
git push
```

Quando tudo estiver funcionando:

```
git switch master
git merge develop
git push
```

---

# Principais comandos Git

## Configuração

Ver usuário

```bash
git config user.name
```

Ver email

```bash
git config user.email
```

Configurar

```bash
git config --global user.name "Julio"
git config --global user.email "julio@email.com"
```

---

## Criar repositório

```bash
git init
```

---

## Adicionar remoto

Usando o seu repositório como exemplo:

```bash
git remote add origin https://github.com/JulioKhichfy/embasa-bet.git
```

Verificar:

```bash
git remote -v
```

---

## Verificar status

```bash
git status
```

---

## Adicionar arquivos

Todos

```bash
git add .
```

Arquivo específico

```bash
git add backend/pom.xml
```

---

## Commit

```bash
git commit -m "Mensagem"
```

---

## Histórico

```bash
git log
```

Resumido

```bash
git log --oneline
```

Em forma de árvore

```bash
git log --graph --oneline --all
```

---

## Branches

Ver

```bash
git branch
```

Criar

```bash
git branch develop
```

Criar e entrar

```bash
git checkout -b develop
```

ou

```bash
git switch -c develop
```

Trocar

```bash
git switch develop
```

Excluir

```bash
git branch -d develop
```

---

## Atualizar repositório

Buscar alterações

```bash
git fetch
```

Baixar e mesclar

```bash
git pull
```

Enviar

```bash
git push
```

Primeira vez

```bash
git push -u origin develop
```

---

## Merge

```bash
git switch master
git merge develop
```

---

## Clonar

```bash
git clone https://github.com/JulioKhichfy/embasa-bet.git
```

---

## Ver diferenças

```bash
git diff
```

Entre commits

```bash
git diff HEAD~1 HEAD
```

---

## Restaurar arquivo

Descartar alterações

```bash
git restore arquivo.java
```

---

## Remover do stage

```bash
git restore --staged arquivo.java
```

---

## Ignorar arquivos já adicionados

```bash
git rm -r --cached frontend/node_modules
```

---

## Ver remotos

```bash
git remote -v
```

---

## Alterar URL do remoto

```bash
git remote set-url origin https://github.com/JulioKhichfy/embasa-bet.git
```

---

# APOIO VI

O **vi** (ou **vim**) é um dos editores de texto mais importantes do Linux. Ele trabalha em **modos**, e conhecer os comandos básicos aumenta muito a produtividade ao editar arquivos de configuração, scripts e código diretamente no terminal.

---

# Abrindo um arquivo

```bash
vi arquivo.txt
```

ou

```bash
vim arquivo.txt
```

Criar um arquivo novo:

```bash
vi novo_arquivo.txt
```

---

# Modos do vi

Existem basicamente três modos:

| Modo     | Função                  |
| -------- | ----------------------- |
| Normal   | Navegação e comandos    |
| Inserção | Digitar texto           |
| Comando  | Salvar, sair, pesquisar |

Sempre que quiser voltar ao modo normal pressione:

```text
ESC
```

---

# Entrar no modo de inserção

| Comando | Ação                      |
| ------- | ------------------------- |
| i       | Insere antes do cursor    |
| I       | Insere no início da linha |
| a       | Insere após o cursor      |
| A       | Insere no final da linha  |
| o       | Nova linha abaixo         |
| O       | Nova linha acima          |

Exemplo:

```text
ESC
i
```

Agora basta digitar normalmente.

---

# Salvar e sair

Salvar:

```text
:w
```

Salvar e sair:

```text
:wq
```

ou

```text
:x
```

Sair sem salvar:

```text
:q!
```

Salvar com outro nome:

```text
:w novo_nome.txt
```

---

# Navegação

Mover um caractere

```text
h ←
l →
k ↑
j ↓
```

Também funcionam as setas do teclado.

---

Ir para:

Início da linha

```text
0
```

Fim da linha

```text
$
```

Primeira linha

```text
gg
```

Última linha

```text
G
```

Linha específica

```text
25G
```

Vai para a linha 25.

---

# Pesquisar

Pesquisar palavra

```text
/palavra
```

Próxima ocorrência

```text
n
```

Ocorrência anterior

```text
N
```

---

# Copiar (Yank)

Copiar linha

```text
yy
```

Copiar 5 linhas

```text
5yy
```

Colar abaixo

```text
p
```

Colar acima

```text
P
```

---

# Recortar

Apagar linha

```text
dd
```

Apagar 3 linhas

```text
3dd
```

Apagar palavra

```text
dw
```

Apagar até o fim da linha

```text
D
```

---

# Desfazer

Desfazer

```text
u
```

Refazer

```text
Ctrl + r
```

---

# Seleção (Visual)

Entrar no modo visual

```text
v
```

Selecionar linhas

```text
V
```

Selecionar bloco

```text
Ctrl + v
```

Depois pode usar:

```text
y
```

(copiar)

ou

```text
d
```

(recortar)

---

# Substituição

Substituir caractere

```text
r
```

Exemplo:

```text
rx
```

Troca o caractere atual por **x**.

---

Trocar palavra

```text
cw
```

Exemplo:

```
Julio
```

Cursor sobre "Julio"

```
cw
Carlos
ESC
```

---

# Localizar e substituir

Na linha atual

```text
:s/velho/novo/
```

Todas as ocorrências da linha

```text
:s/velho/novo/g
```

Arquivo inteiro

```text
:%s/velho/novo/g
```

Perguntar antes de substituir

```text
:%s/velho/novo/gc
```

---

# Mostrar números das linhas

Mostrar

```text
:set number
```

ou

```text
:set nu
```

Ocultar

```text
:set nonumber
```

---

# Ir para uma linha

```text
:100
```

Vai para a linha 100.

---

# Comandos úteis

Voltar ao último arquivo

```text
:e#
```

Recarregar arquivo

```text
:e!
```

Abrir outro arquivo

```text
:e outro.txt
```

---

# Executar comando do Bash

Dentro do vi:

```text
:!ls
```

ou

```text
:!pwd
```

ou

```text
:!git status
```

Após o comando, pressione **Enter** para retornar ao editor.

---

# Inserir conteúdo de um comando

Exemplo:

```text
:r !date
```

Insere no arquivo o resultado do comando:

```bash
date
```

---

# Trabalhando com múltiplos arquivos

Abrir vários arquivos:

```bash
vi arquivo1.txt arquivo2.txt
```

Próximo arquivo

```text
:n
```

Arquivo anterior

```text
:prev
```

---

# Tabela-resumo dos comandos mais usados

| Comando             | Descrição                                |
| ------------------- | ---------------------------------------- |
| `i`                 | Inserir texto                            |
| `a`                 | Inserir após o cursor                    |
| `o`                 | Nova linha abaixo                        |
| `ESC`               | Volta ao modo normal                     |
| `:w`                | Salvar                                   |
| `:q`                | Sair                                     |
| `:wq`               | Salvar e sair                            |
| `:q!`               | Sair sem salvar                          |
| `yy`                | Copiar linha                             |
| `dd`                | Cortar linha                             |
| `p`                 | Colar                                    |
| `u`                 | Desfazer                                 |
| `Ctrl+r`            | Refazer                                  |
| `/texto`            | Pesquisar                                |
| `n`                 | Próxima ocorrência                       |
| `gg`                | Primeira linha                           |
| `G`                 | Última linha                             |
| `0`                 | Início da linha                          |
| `$`                 | Fim da linha                             |
| `dw`                | Apagar palavra                           |
| `cw`                | Alterar palavra                          |
| `:%s/antigo/novo/g` | Substituir em todo o arquivo             |
| `:set nu`           | Mostrar números das linhas               |
| `:!comando`         | Executar um comando do Bash              |
| `:r !comando`       | Inserir a saída de um comando no arquivo |

Para quem trabalha com **Java, Spring Boot, Angular e Git** no Linux, dominar esses comandos já cobre cerca de **90%** das operações realizadas no dia a dia ao editar arquivos como `application.yml`, `pom.xml`, `Dockerfile`, `docker-compose.yml`, scripts `.sh` e arquivos de configuração do Git.

