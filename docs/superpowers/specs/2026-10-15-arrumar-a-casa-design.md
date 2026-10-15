# Arrumar a casa — design

Data: 2026-10-15 · Escopo escolhido: "arrumar a casa" (sem mudar comportamento da API).

## Situação encontrada

- `main` local está 15 commits atrás de `origin/main` (PR #5, `feat/job`). Sem eles o Java local não compila: `core/`, `config/`, `build.gradle` e os testes só existem no remoto. `main` local é ancestral de `origin/main` (fast-forward puro).
- O stage local tem 107 arquivos de `build/`, 5 de `.gradle/` e os ONNX do CLIP (`vision_model.onnx` 336 MB, `text_model.onnx` 243 MB) — acima do limite de 100 MB do GitHub.
- `origin/main` já está Java-only, com Dockerfile multi-stage e `.gitignore` correto, mas:
  - `./gradlew test` **falha** com o JDK 19 instalado: o `build.gradle` pede toolchain 21 e o `settings.gradle` não tem resolver. Com o `foojay-resolver-convention` 1.0.0 o build e os testes passam (verificado em 2026-10-15).
  - `.gradle/` continua versionado (10 arquivos), apesar de estar no `.gitignore`.
  - Nenhum workflow compila ou testa; só existe o `pr-bot.yml`. Um commit "fix: merge failed" entrou sem build.
  - README (linha 174) e `.env.example` (linha 4) mandam gerar a chave com `python -c ...`; o projeto não tem mais Python.
  - O pacote se chama `dataprovaider` (typo de `dataprovider`): 11 arquivos em `src/main`, 7 em `src/test`, 4 classes fora do pacote que o importam (`ExplicadorMorador`, `CalibracaoMain`, `TreinamentoCabecaMain`, `VisaoConfig`) e o README (linha 100).

## Decisões

0. **Sincronizar o local (operação, sem commit):** `git restore --staged .` e `git merge --ff-only origin/main`. Os arquivos ignorados (`build/`, `.gradle/`, `modelos/`, `.venv/`) ficam no disco.
1. **Toolchain:** `settings.gradle` ganha `plugins { id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0' }`. Sucesso = `./gradlew build` passa numa máquina só com JDK 19.
2. **Destrackear `.gradle/`:** `git rm -r --cached .gradle`. Sucesso = `git ls-files .gradle` vazio.
3. **CI:** `.github/workflows/ci.yml` roda `./gradlew build testeBanco` em `pull_request` e em `push` na `main`, Temurin 21, cache do Gradle. O `testeBanco` nunca foi verificado (Docker Desktop desligado em 2026-10-15): roda localmente antes de entrar no CI; se falhar por bug real, sai do `ci.yml`, vira item separado e o CI fica só com `build`. Sem `docker build` no CI (baixaria 600 MB do CLIP por PR; adicionar quando houver deploy automatizado).
4. **Docs:** chave gerada com `openssl rand -base64 32` no README e no `.env.example`; README diz que o Gradle baixa o JDK 21 sozinho.
5. **Rename `dataprovaider` → `dataprovider`:** `git mv` das duas pastas (main e test), troca de `package`/`import`, linha 100 do README. Sucesso = `./gradlew build` passa e `git grep dataprovaider` vazio.

## Fora do escopo

- Apagar branches já mergeadas (`feat/job`, `feat/photo-classifier`, `fix/git-histories`, `backup-feat-validador-fotos`, locais e remotas) — ação no remoto, fica com o dono do repo.
- Itens de produto do README (calibração, HEIC, trust_score) e revisão de qualidade do código.
