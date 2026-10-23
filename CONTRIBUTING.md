# Contribuindo com o Validador de Postagens

Ao participar, você concorda em seguir o [Código de Conduta](CODE_OF_CONDUCT.md). Para reportar um problema de segurança, **não** abra uma issue — siga o [SECURITY.md](SECURITY.md).

## Idioma

A documentação, commits e comentários devem ser em **português do Brasil**.

## Configurando o ambiente

### Pré-requisitos

- JDK 21
- Git
- Docker (para testes de integração)

### Passos

1. Clone o repositório:
   ```bash
   git clone https://github.com/EcoCiente-Instituto-J-F/ds-validador-postagens-api.git
   cd ds-validador-postagens-api
   ```

2. Configure o ambiente local:
   ```bash
   cp .env.example .env
   ```

3. Baixe os arquivos do CLIP (ver [README — Modelo de visão](README.md#modelo-de-visão-clip)):
   ```bash
   mkdir -p modelos/clip-vit-base-patch32 && cd modelos/clip-vit-base-patch32
   B=https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main
   curl -fL -o vision_model.onnx "$B/onnx/vision_model.onnx"
   curl -fL -o text_model.onnx "$B/onnx/text_model.onnx"
   curl -fL -o tokenizer.json "$B/tokenizer.json"
   cd ../..
   ```

4. Verifique a compilação:
   ```bash
   ./gradlew build
   ```

## Testando sua mudança

Toda mudança **deve ter um teste que falha sem ela**. Use:

```bash
./gradlew test                  # unitários, rápido (exclui modelo e banco)
./gradlew testeModelo           # se afeta triagem automática (carrega CLIP)
./gradlew testeBanco            # se afeta persistência ou procedures
./gradlew build                 # teste final antes de abrir PR
```

**Regras**:
- Nenhum teste depende de rede, API paga ou dados de produção
- Dados de teste em `src/test/resources/` e `src/test/java/br/com/ecociente/validador/fixtures/`
- Testes com tag `banco` usam Testcontainers (PostgreSQL em container descartável)
- Testes com tag `modelo` carregam o CLIP de verdade (requer `CLIP_DIR`)

## Regras do projeto

### Autorização centralizada

- Apenas a organização `EcoCiente-Instituto-J-F` merge em `main`
- PRs para `main` requerem CI verde e aprovação de mantenedor

### Configuração de ambiente

- Toda variável de `.env` tem seu padrão bem documentado em `.env.example` e no [README](README.md#variáveis-de-ambiente)
- Se adicionar nova variável:
  1. Declare em `br.com.ecociente.validador.config.PropriedadesValidador` com `@Value`
  2. Documente em `.env.example` com comentário
  3. Atualize a tabela do README

### Sem segredos no repositório

- Nenhum token, senha, chave privada ou ID sensível commitado
- `.env` está em `.gitignore` (use `.env.example` com valores fictícios)
- CLIP vem do Hugging Face durante o build; nunca commite os arquivos ONNX

### Dados pessoais reais não em testes

- Fixtures usam dados fictícios (ex.: usuário ID 999, email fake@test.com)
- Logs jamais expõem URLs de fotos ou dados pessoais (apenas `id_postagem`)

## Pull requests

1. **Crie a branch a partir de `main`**:
   ```bash
   git checkout main
   git pull origin main
   git checkout -b <seu-tipo>/<descrição-curta>
   ```

2. **Commits focados com prefixo padrão**:
   - `feat: ` — nova funcionalidade (ex.: `feat: votos com denúncia`)
   - `fix: ` — correção de bug (ex.: `fix: timezone em fechamento`)
   - `chore: ` — build, deps, CI (ex.: `chore: atualizar Gradle`)
   - `docs: ` — documentação apenas (ex.: `docs: API contract`)
   - `test: ` — testes apenas (ex.: `test: cobertura de voto`)
   - `refactor: ` — sem mudança de comportamento (ex.: `refactor: extrair service`)

3. **Faça push e abra PR**:
   ```bash
   git push origin <seu-tipo>/<descrição>
   ```
   Abra um pull request em <https://github.com/EcoCiente-Instituto-J-F/ds-validador-postagens-api/compare/main>.

4. **CI e revisão**:
   - O PR Bot gera a descrição automaticamente
   - CI (`./gradlew build`) deve passar
   - Aguarde aprovação de um mantenedor

## Reportando bugs

Abra uma issue em <https://github.com/EcoCiente-Instituto-J-F/ds-validador-postagens-api/issues> com:

- **Título**: resumo do problema
- **Reprodução**: passos mínimos
- **Observado vs. esperado**: o que aconteceu e o que deveria acontecer
- **Contexto**: versão Java, SO, stack trace se houver

Para problemas de segurança, **não** abra issue — reporte via [SECURITY.md](SECURITY.md).

## Propondo funcionalidades

Abra uma issue com:

- **Título**: o que propõe
- **Justificativa**: por que é útil para o EcoCiente
- **Esboço**: como faria (se souber)

Aguarde feedback antes de começar o código.

---

Obrigado por contribuir com o EcoCiente! 🌱
