# Arrumar a casa Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repo compila em qualquer máquina, não versiona lixo de build, tem CI e nomes/docs corretos — sem mudar comportamento da API.

**Architecture:** Só configuração e renomeação mecânica. Cada task é um commit na branch `chore/arrumar-a-casa`, que parte de `origin/main` (9939e5d). A "prova" de cada task é um comando que falha antes e passa depois.

**Tech Stack:** Gradle 8.14.3 · Java 21 (toolchain) · Spring Boot 3.5.4 · GitHub Actions

**Spec:** `docs/superpowers/specs/2026-10-15-arrumar-a-casa-design.md`

## Global Constraints

- Nenhuma mudança de comportamento: `./gradlew build` verde ao fim de toda task.
- Comandos em Git Bash, da raiz do repo. Máquina local só tem JDK 19.
- Mensagens de commit no padrão do repo (`chore:`, `ci:`, `docs:`, `refactor:`).
- Ultra/YAGNI (pedido do usuário em 2026-10-15): CI só com `./gradlew build`; `testeBanco` entra quando for visto passando com Docker.

## Review Focus

1. **`gradlew` sem bit de execução (100644)** → no runner Linux `./gradlew` dá Permission denied. Teste: Task 3, `git ls-files -s gradlew` começa com `100755`.
2. **`docker build` com o `settings.gradle` novo** → o stage de build tem JDK 21, então o foojay não baixa nada, mas o plugin precisa resolver do Plugin Portal. Não verificável hoje (Docker desligado); o revisor confere que o Dockerfile copia `settings.gradle` (copia, linha 6).
3. **Referência textual ao pacote antigo fora de `.java`** (properties, README, strings de reflexão) → rename quebra em runtime, não na compilação. Teste: Task 5, `git grep dataprovaider` vazio.
4. **Máquina sem internet no primeiro build** → foojay não baixa o JDK e o build falha com mensagem do Gradle. Aceito; README diz que o primeiro build baixa o JDK 21.
5. **`.gradle/` local ainda no disco após destrackear** → continua ignorado, nada volta ao stage. Teste: Task 2, `git status --short .gradle` vazio.

---

### Task 1: Toolchain resolvível

**Files:** Modify: `settings.gradle`

- [ ] **Step 1: Ver falhar** — Run: `./gradlew build -q` · Expected: FAIL, toolchain Java 21 não encontrada.
- [ ] **Step 2: Implementar** — no topo de `settings.gradle`: `plugins { id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0' }`.
- [ ] **Step 3: Ver passar** — Run: `./gradlew build` · Expected: BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** — `chore: resolve o JDK 21 automaticamente (foojay)`; inclui o spec e este plano.

### Task 2: Destrackear `.gradle/`

**Files:** Remove do índice: `.gradle/**` (10 arquivos)

- [ ] **Step 1: Ver falhar** — Run: `git ls-files .gradle | wc -l` · Expected: `10`.
- [ ] **Step 2: Implementar** — `git rm -r --cached -q .gradle`.
- [ ] **Step 3: Ver passar** — Run: `git ls-files .gradle | wc -l; git status --short .gradle | grep -v '^D'` · Expected: `0` e nenhuma linha extra.
- [ ] **Step 4: Commit** — `chore: para de versionar o cache .gradle`.

### Task 3: CI

**Files:** Create: `.github/workflows/ci.yml` · Modify (modo): `gradlew`

- [ ] **Step 1: Ver falhar** — Run: `git ls-files -s gradlew` · Expected: `100644`.
- [ ] **Step 2: Implementar** — `git update-index --chmod=+x gradlew`; `ci.yml` com `on: pull_request` e `push: branches: [main]`, `permissions: contents: read`, um job `build` em `ubuntu-latest`: `actions/checkout@v7`, `actions/setup-java@v4` (`distribution: temurin`, `java-version: 21`), `gradle/actions/setup-gradle@v4`, `./gradlew build`.
- [ ] **Step 3: Ver passar** — Run: `git ls-files -s gradlew; ./gradlew build -q` · Expected: `100755` e build verde.
- [ ] **Step 4: Commit** — `ci: build e testes unitários em PR e na main`.

### Task 4: Docs sem Python

**Files:** Modify: `README.md:174`, `.env.example:4`, `README.md` (seção de pré-requisitos/instalação)

- [ ] **Step 1: Ver falhar** — Run: `git grep -n 'python -c'` · Expected: 2 linhas.
- [ ] **Step 2: Implementar** — trocar por `openssl rand -base64 32`; uma linha no README: o primeiro `./gradlew build` baixa o JDK 21 se a máquina não tiver.
- [ ] **Step 3: Ver passar** — Run: `git grep -n 'python -c'` · Expected: vazio.
- [ ] **Step 4: Commit** — `docs: chave gerada com openssl e nota do JDK 21`.

### Task 5: Rename `dataprovaider` → `dataprovider`

**Files:** Move: `src/{main,test}/java/br/com/ecociente/validador/dataprovaider/` → `.../dataprovider/` · Modify: `package`/`import` em todo `.java` que cita o pacote, `README.md:100`

- [ ] **Step 1: Ver falhar** — Run: `git grep -l dataprovaider | wc -l` · Expected: `> 0` (22).
- [ ] **Step 2: Implementar** — `git mv` das duas pastas; `sed -i 's/dataprovaider/dataprovider/g'` nos arquivos listados por `git grep -l dataprovaider`.
- [ ] **Step 3: Ver passar** — Run: `git grep -l dataprovaider | wc -l; ./gradlew build -q` · Expected: `0` e build verde (mesma contagem de testes que antes).
- [ ] **Step 4: Commit** — `refactor: corrige o nome do pacote dataprovider`.
