# API de Validação de Postagens — Design

Data: 07/10/2026 · Status: aprovado em conversa (seções 1 e 2) · Sem commits por pedido do autor.

## Objetivo

O EcoCiente terá duas APIs:

- **API de Pontuação e Ranking** (Java/Spring, outro repo): dona do ledger `tb_movimentacoes_pontos` e do Redis.
- **API de Validação** (este repo, Python/FastAPI): dona do ciclo de validação de `tb_postagens` no PostgreSQL —
  triagem automática (CLIP, já existe), votos ponderados da comunidade, fechamento da janela de 24h, decisão manual
  do síndico e recálculo do trust_score.

As regras de negócio **já estão no banco** (`ecociente_schema.sql`, seções 11 e 12). Esta API não reimplementa
nenhuma: chama os procedures e grava a triagem.

## Decisões (com o porquê)

| Decisão | Porquê |
|---|---|
| O backend do app faz o `INSERT` em `tb_postagens` (com `hash_foto` SHA-256) e chama esta API pelo `id_postagem` | Fronteira menor; esta API não é dona da postagem |
| Votos e decisões chegam **via backend do app** com `usuario_id`, autenticados por `X-Api-Key` | Mantém a API interna (`ClusterIP`), sem login de usuário aqui |
| `hash_foto` fica SHA-256 (backend); o dHash do validador só vai para o log | O `UNIQUE` barra cópia exata; o motivo de denúncia "foto reutilizada" cobre o resto |
| Triagem reprovada **só grava o sinal** (`triagem_automatica_aprovada/_confianca`) | Nenhum procedure muda; a comunidade decide. Limite conhecido: foto aleatória sem nenhum voto fecha com saldo 0 → aprovada |
| Autovoto (autor votando na própria postagem) é **bloqueado** com 403 | O procedure hoje permite |
| Fechamento das janelas por **CronJob do k3s** (`python -m src.fechamento`, a cada 5 min, `concurrencyPolicy: Forbid`) | Processo separado, não carrega o CLIP, não disputa CPU com a API |
| Integração com a API de Pontuação **só pelo banco**: esta API liga `pontuacao_reconciliacao_pendente` (via procedures); a de Pontuação lê | Nenhuma chamada HTTP entre as APIs; esta API nunca escreve no ledger |
| `POST /v1/validacoes` (stateless) é **removido** | O backend passa a usar a rota nova; agentes usam a `ValidarFotoTool` direto |
| psycopg 3, SQL cru, uma conexão por pedido | Regras ficam nos procedures; conexão por pedido custa ms contra segundos de CLIP (`psycopg_pool` se medir gargalo) |

## Rotas (todas exigem `X-Api-Key`)

### `POST /v1/postagens/{id_postagem}/triagem`

1. Conexão curta: lê `url_foto` e `nome_categoria` (JOIN `tb_lkp_categorias_residuos`). Postagem inexistente → 404.
2. Fecha a conexão e roda a `ValidarFotoTool` (download anti-SSRF + CLIP) **sem conexão aberta**, com o mesmo limite
   de pedidos simultâneos (503 + `Retry-After: 5`), métricas e linha de log JSON de hoje (log agora inclui `id_postagem`, nunca a URL).
3. Categoria do banco fora do catálogo do CLIP → 422; host fora de `HOSTS_PERMITIDOS` → 422; storage falhou → 502. Nesses casos não grava.
4. Conexão curta: `UPDATE tb_postagens SET triagem_automatica_aprovada = pertinente, triagem_automatica_confianca = confianca`.
   `pertinente: null` (zona de revisão) grava `NULL`.
5. Resposta 200: o mesmo JSON `ValidarFotoOutput` de hoje.

### `POST /v1/postagens/{id_postagem}/votos`

Corpo: `{"usuario_id": int, "tipo": "aprovar"|"denunciar", "motivo_denuncia_id": int|null, "comentario": str|null (≤255)}`.

Numa transação: lê o autor (404 se não existe; 403 se `usuario_id` = autor) → `CALL sp_processar_voto_postagem` →
lê `saldo_confianca, pontuacao_ativa` → commit. Resposta 200: `{"saldo_confianca": int, "pontuacao_ativa": bool}`.

### `POST /v1/postagens/{id_postagem}/decisao`

Corpo: `{"usuario_id": int, "aprovar": bool}`.

Numa transação: confere se `usuario_id` é o síndico do condomínio da postagem
(`tb_condominios.sindico_id → tb_sindicos.usuario_id`; 404 se a postagem não existe, 403 se não é o síndico) →
`CALL sp_decidir_postagem_analise` → recalcula o trust_score dos envolvidos. Resposta 204.

### Mapeamento de erros do banco (todas as rotas)

| Exceção | HTTP |
|---|---|
| `psycopg.errors.RaiseException` (os `RAISE EXCEPTION` dos procedures: janela encerrada, sem vínculo, motivo obrigatório, não está em análise…) | 422, `detail` = mensagem do banco |
| `psycopg.errors.UniqueViolation` (voto repetido) | 409 |
| `psycopg.errors.ForeignKeyViolation` (motivo de denúncia inexistente) | 422 |

## Job de fechamento — `python -m src.fechamento`

- Lê só `URL_BANCO` do ambiente; não carrega o CLIP. Mesma imagem Docker, outro `command`.
- `SELECT id_postagem FROM tb_postagens WHERE resolvido_em IS NULL AND data_limite_analise <= now() ORDER BY data_limite_analise LIMIT 1000`.
- Uma transação por postagem: `CALL sp_encerrar_janela_postagem` → trust_score dos envolvidos. Falha numa postagem: log e segue.
- Sai com código 1 se alguma falhou (o k3s mostra), 0 caso contrário.

**Envolvidos** (trust_score): o autor e cada votante da postagem, no condomínio da postagem, **apenas os que têm linha em
`tb_rel_usuarios_condominios`** (o procedure levanta exceção sem vínculo e travaria o fechamento para sempre).

## Configuração

- Nova variável obrigatória da API e do job: `URL_BANCO` (ex.: `postgresql://usuario:senha@host:5432/ecociente`), vinda de um Secret.
- Nova dependência: `psycopg[binary]`.

## Testes

- **Unitários (rodam sempre):** rotas com as funções de `src/banco.py` substituídas por dublês (monkeypatch) — códigos HTTP,
  autovoto, conexão fechada durante o CLIP, mapeamento de erros, log, métricas, 503. Job com dublês: uma falha não para as outras; código de saída.
- **Integração (`pytest -m banco`, precisa de `URL_BANCO_TESTE` apontando para um Postgres descartável — o teste apaga o schema `public`):**
  recria o schema a partir de `tests/sql/ecociente_schema.sql` (cópia do script da modelagem) + `tests/sql/seed_lookups.sql`, e testa
  as funções de `src/banco.py` contra os procedures reais: pesos 1/3, histerese -5/≥0, autovoto, voto repetido, as 3 faixas do fechamento,
  decisão do síndico, trust_score.
- A cópia do schema pode ficar desatualizada em relação à pasta da modelagem; atualizar a cópia faz parte de mudar o schema.

## Fora do escopo (YAGNI)

- Notificar a API de Pontuação (ela lê a flag no banco).
- Endpoint de consulta de postagem (o backend lê o banco).
- Pool de conexões; manifests k8s no repo (o README traz o trecho do CronJob).

## Para a equipe de dados (não entra nesta API)

1. O schema não tem seed de `tb_lkp_niveis_confianca`, `tb_lkp_status_validacoes_postagens`, `tb_lkp_tipos_votos_postagens`,
   `tb_lkp_motivos_denuncia`. Os defaults `status_validacao_id = 1` (aprovada) e `nivel_confianca_id = 1` (morador_comum)
   dependem da ordem do seed. `tests/sql/seed_lookups.sql` serve de modelo.
2. `sp_atualizar_trust_score` conta `denuncias_realizadas`/`procedentes` de todos os condomínios, mas grava no vínculo de um só.
3. Quem cria o vínculo do síndico deve usar `nivel_confianca = sindico` para o peso 3.
