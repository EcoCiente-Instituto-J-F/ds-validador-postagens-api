# API de Validação de Postagens — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transformar o validador stateless em API de validação que grava a triagem em `tb_postagens`, registra votos, decide postagens em análise e fecha as janelas de 24h chamando os procedures do PostgreSQL.

**Architecture:** `src/banco.py` concentra o SQL (uma função por operação, recebendo uma conexão psycopg; as regras ficam nos procedures). `src/api/app.py` troca `POST /v1/validacoes` por três rotas sob `/v1/postagens/{id}`. `src/fechamento.py` é o CronJob que fecha janelas vencidas.

**Tech Stack:** Python 3.12+ · FastAPI · psycopg 3 (`psycopg[binary]`) · PostgreSQL 14+ · pytest

**Spec:** `docs/superpowers/specs/2026-10-07-api-validacao-postagens-design.md`

## Global Constraints

- **SEM COMMITS.** Nenhum `git commit`. Ao criar arquivo novo, rode `git add -N <arquivo>` (intent-to-add) para ele aparecer no `git diff`. Nunca `git add` de conteúdo, nunca `git stash`, nunca `git reset`.
- Python da venv: `.venv/Scripts/python` (Windows, Git Bash). Testes: `.venv/Scripts/python -m pytest`.
- Integração: `URL_BANCO_TESTE=postgresql://postgres:teste@localhost:55432/postgres` (container `ecociente-pg-teste`, `postgres:16`, já criado pelo controlador). Se o banco não responder, rode os unitários e relate `DONE_WITH_CONCERNS`.
- Nada de pool de conexões, ORM ou nova abstração além do que cada task define (YAGNI).
- Variável de ambiente nova: `URL_BANCO` (API e job). Dependência nova: `psycopg[binary]>=3.2`.
- Mensagens ao usuário e nomes no código em português, como o resto do repo; comentários curtos, só o porquê.
- A URL da foto nunca vai para o log (pode carregar token de URL assinada).

## Review Focus

1. **Postagem cujo autor/votante não tem vínculo em `tb_rel_usuarios_condominios`** — o fechamento não pode travar para sempre: `atualizar_trust_scores` ignora quem não tem vínculo (teste na Task 1).
2. **Categoria do banco fora do catálogo do CLIP** (ex.: "Eletrônicos") — 422 sem gravar, não 500 (teste na Task 2).
3. **Conexão aberta durante o CLIP** — a rota de triagem fecha a conexão antes da inferência (teste na Task 2).
4. **Uma postagem quebrada no lote do fechamento** — as outras fecham e o processo sai com 1 (teste na Task 3).
5. **Voto repetido / janela encerrada / motivo inexistente** — 409 / 422 / 422, nunca 500 (testes na Task 2).

---

### Task 1: `src/banco.py` + infraestrutura de testes de integração

**Files:**
- Modify: `requirements.txt` (adicionar `psycopg[binary]>=3.2`), `pyproject.toml` (marker `banco`; `addopts = "-m 'not modelo and not banco'"`)
- Create: `tests/sql/ecociente_schema.sql` — cópia byte a byte de `C:/Users/heitormiasato-ieg/OneDrive - Instituto J&F/Área de Trabalho/Segundo Ano TECH/TECH/Modelagem de Dados/inter/ecociente_schema.sql` (use `cp`)
- Create: `tests/sql/seed_lookups.sql`, `tests/banco/conftest.py`, `tests/banco/test_banco.py`
- Create: `src/banco.py`

**Interfaces:**
- Produces (`src/banco.py`), todas recebem `conexao: psycopg.Connection` como 1º argumento e **não** fazem commit (quem abre a conexão decide):
  - `class PostagemNaoEncontrada(Exception)`, `class AutoVoto(Exception)`, `class NaoESindico(Exception)`
  - `dados_triagem(conexao, id_postagem: int) -> tuple[str, str]` → `(url_foto, nome_categoria)`; levanta `PostagemNaoEncontrada`
  - `gravar_triagem(conexao, id_postagem: int, aprovada: bool | None, confianca: float | None) -> None`
  - `votar(conexao, id_postagem: int, usuario_id: int, tipo: str, motivo_denuncia_id: int | None, comentario: str | None) -> tuple[int, bool]` → `(saldo_confianca, pontuacao_ativa)`; levanta `PostagemNaoEncontrada`, `AutoVoto` (antes do CALL); erros do procedure sobem como `psycopg.errors.*`
  - `decidir(conexao, id_postagem: int, usuario_id: int, aprovar: bool) -> None` — confere síndico via `tb_condominios.sindico_id → tb_sindicos.usuario_id`; levanta `PostagemNaoEncontrada`, `NaoESindico`; depois `CALL sp_decidir_postagem_analise` e `atualizar_trust_scores`
  - `atualizar_trust_scores(conexao, id_postagem: int) -> None` — `CALL sp_atualizar_trust_score(usuario, condominio)` para autor + votantes, no condomínio da postagem, **só quem tem linha em `tb_rel_usuarios_condominios`** (JOIN no SELECT)
  - `janelas_vencidas(conexao, limite: int = 1000) -> list[int]` — `resolvido_em IS NULL AND data_limite_analise <= now()`, `ORDER BY data_limite_analise`
  - `encerrar_janela(conexao, id_postagem: int) -> None` — `CALL sp_encerrar_janela_postagem` + `atualizar_trust_scores`
- Produces (`tests/banco/conftest.py`): fixture `conexao` (autocommit; recria o schema `public` a cada teste a partir de `tests/sql/ecociente_schema.sql` + `seed_lookups.sql`; `pytest.skip` sem `URL_BANCO_TESTE`); fixture `mundo` (SimpleNamespace: `condominio`, `sindico` (id de usuário), `autor`, `comuns` (5 ids, peso 1), `confiaveis` (4 ids, peso 3), `sem_vinculo` (1 id de usuário sem vínculo), `postagem` (id, do `autor`)); helper `nova_postagem(conexao, autor, condominio, hash) -> int`; helper `vencer(conexao, id_postagem)` (`UPDATE ... SET data_limite_analise = now() - interval '1 minute'`). Todos os testes do diretório levam `pytestmark = pytest.mark.banco`.

`tests/sql/seed_lookups.sql` (exato — a ordem fixa os ids 1 dos defaults):

```sql
INSERT INTO tb_lkp_niveis_confianca (nome_nivel, peso_voto) VALUES ('morador_comum', 1), ('pessoa_confiavel', 3), ('sindico', 3);
INSERT INTO tb_lkp_status_validacoes_postagens (nome_status) VALUES ('aprovada'), ('em_analise'), ('reprovada');
INSERT INTO tb_lkp_tipos_votos_postagens (nome_tipo) VALUES ('aprovar'), ('denunciar');
INSERT INTO tb_lkp_motivos_denuncia (descricao) VALUES ('Nao e lixo reciclavel'), ('Foto nao corresponde a categoria'), ('Foto antiga-reutilizada'), ('Spam-abuso');
INSERT INTO tb_lkp_categorias_residuos (nome_categoria, pontos_base, limite_pontos_diario) VALUES ('Plástico', 10, 25), ('Eletrônicos', 10, NULL);
INSERT INTO tb_lkp_tipos_usuarios (nome_tipo) VALUES ('morador_residencial');
INSERT INTO tb_lkp_tipos_condominios (nome_tipo) VALUES ('residencial');
```

O script do schema roda inteiro num `conexao.execute(texto)` sem parâmetros (psycopg aceita várias instruções assim). Vínculos: `aprovado = TRUE`, contadores e `trust_score` em 0, `nivel_confianca_id` pelo `nome_nivel`. O síndico tem linha em `tb_sindicos`, é o `sindico_id` do condomínio e tem vínculo `sindico`. A postagem do `mundo` usa a categoria `Plástico` e `url_foto = 'https://storage.exemplo.com/postagens/1.png'`.

- [ ] **Step 1: infraestrutura** — dependência, marker, cópia do schema, seed, `conftest.py`, e um teste `test_schema_sobe(conexao)` que conta 3 linhas em `tb_lkp_niveis_confianca`. Rode `URL_BANCO_TESTE=... .venv/Scripts/python -m pytest -m banco tests/banco -v` → PASS. (Se o script do schema falhar no Postgres, relate o erro exato — é achado para a equipe de dados, não conserte o schema.)

- [ ] **Step 2: testes que falham** em `tests/banco/test_banco.py`:

```python
def test_dados_triagem_le_url_e_categoria(conexao, mundo):
    assert banco.dados_triagem(conexao, mundo.postagem) == ("https://storage.exemplo.com/postagens/1.png", "Plástico")

def test_postagem_inexistente(conexao, mundo):
    with pytest.raises(banco.PostagemNaoEncontrada):
        banco.dados_triagem(conexao, 999_999)
    with pytest.raises(banco.PostagemNaoEncontrada):
        banco.votar(conexao, 999_999, mundo.comuns[0], "aprovar", None, None)
    with pytest.raises(banco.PostagemNaoEncontrada):
        banco.decidir(conexao, 999_999, mundo.sindico, True)

def test_gravar_triagem_inclusive_nulo(conexao, mundo):
    banco.gravar_triagem(conexao, mundo.postagem, False, 87.42)
    # SELECT triagem_automatica_aprovada, triagem_automatica_confianca -> (False, Decimal("87.42"))
    banco.gravar_triagem(conexao, mundo.postagem, None, None)
    # -> (None, None)

def test_voto_soma_o_peso_do_nivel(conexao, mundo):
    assert banco.votar(conexao, mundo.postagem, mundo.comuns[0], "aprovar", None, None) == (1, True)
    assert banco.votar(conexao, mundo.postagem, mundo.confiaveis[0], "denunciar", 1, "não é lixo") == (-2, True)

def test_autor_nao_vota_na_propria_postagem(conexao, mundo):
    with pytest.raises(banco.AutoVoto):
        banco.votar(conexao, mundo.postagem, mundo.autor, "aprovar", None, None)
    # nenhuma linha em tb_rel_votos_postagens

def test_voto_repetido(conexao, mundo):
    banco.votar(conexao, mundo.postagem, mundo.comuns[0], "aprovar", None, None)
    with pytest.raises(psycopg.errors.UniqueViolation):
        banco.votar(conexao, mundo.postagem, mundo.comuns[0], "denunciar", 1, None)

def test_denuncia_sem_motivo_e_janela_encerrada_viram_raise_exception(conexao, mundo):
    with pytest.raises(psycopg.errors.RaiseException):
        banco.votar(conexao, mundo.postagem, mundo.comuns[0], "denunciar", None, None)
    vencer(conexao, mundo.postagem)
    with pytest.raises(psycopg.errors.RaiseException):
        banco.votar(conexao, mundo.postagem, mundo.comuns[1], "aprovar", None, None)

def test_histerese_retira_em_menos_5_e_restaura_em_zero(conexao, mundo):
    banco.votar(conexao, mundo.postagem, mundo.confiaveis[0], "denunciar", 1, None)
    assert banco.votar(conexao, mundo.postagem, mundo.confiaveis[1], "denunciar", 1, None) == (-6, False)
    banco.votar(conexao, mundo.postagem, mundo.confiaveis[2], "aprovar", None, None)
    assert banco.votar(conexao, mundo.postagem, mundo.confiaveis[3], "aprovar", None, None) == (0, True)
    # pontuacao_reconciliacao_pendente == True

@pytest.mark.parametrize(("denuncias_comuns", "status"), [(0, "aprovada"), (1, "em_analise"), (5, "reprovada")])
def test_encerrar_janela_nas_tres_faixas(conexao, mundo, denuncias_comuns, status):
    for usuario in mundo.comuns[:denuncias_comuns]:
        banco.votar(conexao, mundo.postagem, usuario, "denunciar", 1, None)
    vencer(conexao, mundo.postagem)
    assert banco.janelas_vencidas(conexao) == [mundo.postagem]
    banco.encerrar_janela(conexao, mundo.postagem)
    # nome_status da postagem == status; resolvido_em IS NOT NULL
    assert banco.janelas_vencidas(conexao) == []

def test_janela_aberta_nao_aparece_como_vencida(conexao, mundo):
    assert banco.janelas_vencidas(conexao) == []

def test_denuncia_procedente_conta_no_trust_score(conexao, mundo):
    for usuario in mundo.comuns:
        banco.votar(conexao, mundo.postagem, usuario, "denunciar", 1, None)
    vencer(conexao, mundo.postagem)
    banco.encerrar_janela(conexao, mundo.postagem)
    # vínculo de mundo.comuns[0]: denuncias_realizadas == 1, denuncias_procedentes == 1, trust_score == Decimal("55.00")

def test_fechamento_ignora_envolvido_sem_vinculo(conexao, mundo):
    postagem = nova_postagem(conexao, mundo.sem_vinculo, mundo.condominio, "hash-sem-vinculo")
    vencer(conexao, postagem)
    banco.encerrar_janela(conexao, postagem)  # não levanta
    # nome_status == "aprovada"

def test_decisao_so_do_sindico_e_so_em_analise(conexao, mundo):
    with pytest.raises(banco.NaoESindico):
        banco.decidir(conexao, mundo.postagem, mundo.comuns[0], True)
    with pytest.raises(psycopg.errors.RaiseException):  # ainda não está em análise
        banco.decidir(conexao, mundo.postagem, mundo.sindico, True)
    banco.votar(conexao, mundo.postagem, mundo.comuns[0], "denunciar", 1, None)
    vencer(conexao, mundo.postagem)
    banco.encerrar_janela(conexao, mundo.postagem)
    banco.decidir(conexao, mundo.postagem, mundo.sindico, False)
    # nome_status == "reprovada"; pontuacao_ativa == False
```

Escreva os comentários `#` como asserts de verdade (SELECT na conexão). Rode e veja falhar por `src.banco` inexistente.

- [ ] **Step 3: implemente `src/banco.py`** conforme Interfaces. `votar` lê o autor, compara, faz o CALL e devolve o SELECT de `saldo_confianca, pontuacao_ativa`. Comentário `# ponytail:` na abertura do módulo não é necessário — quem conecta é o chamador.

- [ ] **Step 4: rode** `URL_BANCO_TESTE=... .venv/Scripts/python -m pytest -m banco tests/banco -v` → todos PASS; e `.venv/Scripts/python -m pytest -q` → a suíte unitária continua verde (os de banco não rodam sem `-m banco`).

- [ ] **Step 5: `git add -N`** dos arquivos novos. Sem commit.

---

### Task 2: rotas da API sobre `src/banco.py`

**Files:**
- Modify: `src/api/app.py`, `tests/api/test_app.py`, `README.md` (seção "Contrato HTTP" e tabela de variáveis), `.env.example` (`URL_BANCO=postgresql://postgres:teste@localhost:55432/postgres`)

**Interfaces:**
- Consumes: tudo de `src/banco.py` (Task 1).
- Produces:
  - `Configuracoes.url_banco: str` (obrigatório, `description="URL do PostgreSQL (postgresql://...)."`)
  - `criar_app(configuracoes=None, ferramenta=None, conectar: Callable[[], ContextManager[psycopg.Connection]] | None = None) -> FastAPI`; padrão `lambda: psycopg.connect(configuracoes.url_banco)` (o `with` do psycopg dá commit no sucesso, rollback na exceção e fecha).
  - As rotas chamam `banco.<função>` pelo módulo (`from src import banco`), para os testes trocarem com `monkeypatch.setattr(banco, ...)`.
  - Corpos: `Voto(BaseModel)`: `usuario_id: int`, `tipo: Literal["aprovar", "denunciar"]`, `motivo_denuncia_id: int | None = None`, `comentario: str | None = Field(default=None, max_length=255)`. `Decisao(BaseModel)`: `usuario_id: int`, `aprovar: bool`. Resposta do voto: `{"saldo_confianca": int, "pontuacao_ativa": bool}`. Decisão: 204 sem corpo.
  - Exception handlers no app: `banco.PostagemNaoEncontrada` → 404 `"Postagem não encontrada."`; `banco.AutoVoto` → 403 `"O autor não pode votar na própria postagem."`; `banco.NaoESindico` → 403 `"Só o síndico do condomínio decide postagens em análise."`; `psycopg.errors.RaiseException` → 422 com `detail = erro.diag.message_primary`; `psycopg.errors.UniqueViolation` → 409 `"Usuário já votou nesta postagem."`; `psycopg.errors.ForeignKeyViolation` → 422 `"Referência inválida (motivo de denúncia inexistente?)."`.
  - Rota `POST /v1/validacoes` **removida**. Linha de log do veredito ganha `"id_postagem"`.
  - Triagem: categoria do banco que `ValidarFotoInput` rejeita (`pydantic.ValidationError`) → 422 `f"Categoria fora do catálogo do validador: {categoria}"`, sem chamar a tool nem gravar.

- [ ] **Step 1: reescreva `tests/api/test_app.py`** — mantenha todos os testes atuais de comportamento (veredito completo, outra categoria, não-imagem, 401, host não permitido 422, storage 502, saúde, chave curta, rotação de chave, chave vazia, log sem URL, métricas, variável vazia, 503 com `Retry-After`, vaga devolvida, URL assinada fora do log), agora via `POST /v1/postagens/42/triagem`, com `banco.dados_triagem` trocado por um dublê que devolve `(URL_FOTO, "Plástico")` (ou a URL do teste) e `conectar=lambda: contextlib.nullcontext(None)`. `Configuracoes(...)` dos testes ganha `url_banco="postgresql://teste"`. Acrescente:

```python
def test_triagem_grava_veredito(...):      # gravar_triagem chamado com (42, True, 91.23)
def test_triagem_nao_grava_quando_storage_falha(...):   # 502 e gravar_triagem NÃO chamado
def test_conexao_fechada_durante_o_clip(...):  # conectar devolve um context manager que marca aberto/fechado; o classificador falso assert que nenhuma conexão está aberta ao rodar
def test_categoria_do_banco_fora_do_catalogo_422(...):  # dados_triagem -> (URL_FOTO, "Eletrônicos"): 422, detail menciona "Eletrônicos", tool não chamada, nada gravado
def test_triagem_postagem_inexistente_404(...):  # dados_triagem levanta PostagemNaoEncontrada
def test_log_traz_id_postagem(...):         # registro["id_postagem"] == 42
def test_voto_devolve_saldo(...):           # votar -> (3, True): 200 {"saldo_confianca": 3, "pontuacao_ativa": True}; dublê recebeu (42, 7, "aprovar", None, None)
def test_autovoto_403(...)
def test_voto_repetido_409(...)             # dublê levanta psycopg.errors.UniqueViolation("x")
def test_erro_do_procedure_vira_422_com_a_mensagem(...)  # dublê levanta RaiseException; detail == a mensagem primária (monte a exceção como o psycopg permitir; se diag não for configurável, troque `.diag.message_primary` por `str(erro)` no app e ajuste o teste — registre no relatório)
def test_motivo_inexistente_422(...)        # ForeignKeyViolation
def test_tipo_de_voto_invalido_422(...)     # tipo="curtir": validação do FastAPI
def test_decisao_204(...)                   # decidir chamado com (42, 1, True)
def test_decisao_de_nao_sindico_403(...)
def test_rota_antiga_sumiu(...)             # POST /v1/validacoes -> 404
def test_rotas_novas_exigem_chave(...)      # votos e decisao sem X-Api-Key -> 401
```

Rode e veja falhar.

- [ ] **Step 2: implemente** em `src/api/app.py`. Na triagem, a ordem é: conexão curta para `dados_triagem` → valida `ValidarFotoInput` → semáforo + tool + métricas + log (código atual) → se `status != "ok"` levanta como hoje → conexão curta para `gravar_triagem` → devolve o resultado. Atualize a docstring do módulo.

- [ ] **Step 3: rode** `.venv/Scripts/python -m pytest -q` → tudo verde.

- [ ] **Step 4: README e `.env.example`** — substitua a seção "Contrato HTTP" pelas três rotas (corpo, respostas, tabela de status: 200/204, 401, 403, 404, 409, 422, 502, 503 e o que o backend faz em cada), diga que a API grava `triagem_automatica_*` e que a reconciliação de pontos fica com a API de Pontuação via `pontuacao_reconciliacao_pendente`; acrescente `URL_BANCO` na tabela de variáveis; remova o trecho "o chamador grava ...". Atualize a frase de abertura (deixa de ser stateless).

- [ ] **Step 5:** sem commit.

---

### Task 3: job de fechamento `python -m src.fechamento`

**Files:**
- Create: `src/fechamento.py`, `tests/test_fechamento.py`
- Modify: `tests/banco/test_banco.py` (um teste de integração do job), `README.md` (seção "Deploy (k3s)": CronJob)

**Interfaces:**
- Consumes: `banco.janelas_vencidas`, `banco.encerrar_janela` (Task 1).
- Produces:
  - `fechar_vencidas(conexao: psycopg.Connection) -> int` — recebe conexão **autocommit**; lista as vencidas; para cada uma, `with conexao.transaction(): banco.encerrar_janela(conexao, id)`; `psycopg.Error` → `log.exception(...)` com o id e segue; devolve o número de falhas. Logger: `validador_fotos.fechamento`; ao fim, um `info` com encerradas/falhas.
  - `main() -> None` — `logging.basicConfig(level=logging.INFO)`; abre `psycopg.connect(os.environ["URL_BANCO"], autocommit=True)`; `sys.exit(1 if falhas else 0)`. `if __name__ == "__main__": main()`.

- [ ] **Step 1: testes que falham** — `tests/test_fechamento.py` (unitário, sem banco; conexão falsa cujo `transaction()` devolve `contextlib.nullcontext()`; `monkeypatch` em `banco.janelas_vencidas`/`banco.encerrar_janela`):

```python
def test_encerra_todas_as_vencidas():          # [1, 2, 3] -> encerrar chamado 3x, devolve 0
def test_uma_falha_nao_para_as_outras(caplog):  # encerrar levanta psycopg.errors.RaiseException no id 2 -> 1 e 3 encerrados, devolve 1, log cita o id 2
def test_main_sai_com_1_quando_ha_falha(monkeypatch):  # URL_BANCO setado, psycopg.connect trocado por dublê, fechar_vencidas -> 1: SystemExit.code == 1
```

E em `tests/banco/test_banco.py`:

```python
def test_job_fecha_as_vencidas(conexao, mundo):
    outra = nova_postagem(conexao, mundo.autor, mundo.condominio, "hash-2")  # janela aberta
    vencer(conexao, mundo.postagem)
    assert fechamento.fechar_vencidas(conexao) == 0
    # mundo.postagem resolvida; outra continua com resolvido_em IS NULL
```

- [ ] **Step 2: implemente `src/fechamento.py`.**

- [ ] **Step 3: rode** `.venv/Scripts/python -m pytest -q` e `URL_BANCO_TESTE=... .venv/Scripts/python -m pytest -m banco tests/banco -v` → verdes.

- [ ] **Step 4: README** — na seção de deploy, um CronJob mínimo (YAML): `schedule: "*/5 * * * *"`, `concurrencyPolicy: Forbid`, mesma imagem, `command: ["python", "-m", "src.fechamento"]`, `URL_BANCO` vindo do Secret, `restartPolicy: Never`. Uma linha: o job não carrega o CLIP.

- [ ] **Step 5: `git add -N`** dos arquivos novos. Sem commit.
