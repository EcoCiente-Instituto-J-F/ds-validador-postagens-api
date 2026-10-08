# Validador de Postagens — EcoCiente

![GitHub repo size](https://img.shields.io/github/repo-size/EcoCiente-Instituto-J-F/ds-validador-postagens-api?style=for-the-badge)
![GitHub language count](https://img.shields.io/github/languages/count/EcoCiente-Instituto-J-F/ds-validador-postagens-api?style=for-the-badge)
![GitHub forks](https://img.shields.io/github/forks/EcoCiente-Instituto-J-F/ds-validador-postagens-api?style=for-the-badge)
![GitHub open issues](https://img.shields.io/github/issues/EcoCiente-Instituto-J-F/ds-validador-postagens-api?style=for-the-badge)
![GitHub open pull requests](https://img.shields.io/github/issues-pr/EcoCiente-Instituto-J-F/ds-validador-postagens-api?style=for-the-badge)

> API de validação das postagens de reciclagem do EcoCiente. Faz a triagem automática da foto com um modelo de visão (CLIP, só CPU), registra os votos ponderados da comunidade e a decisão do síndico, e fecha a janela de validação de 24h, tudo sobre o PostgreSQL do app. A pontuação e o ranking ficam com a API de Pontuação, que lê `pontuacao_reconciliacao_pendente`.

A postagem precisa de condomínio: usuário comum (sem condomínio) não posta foto. A triagem é um sinal auxiliar; quem decide é a votação comunitária. As regras de voto, histerese (-5 / ≥ 0), fechamento e trust_score vivem nos procedures do banco; a API só os chama.

## Ajustes e melhorias

O projeto ainda está em desenvolvimento e as próximas atualizações serão voltadas para as seguintes tarefas:

- [x] Triagem automática com CLIP gravando `triagem_automatica_aprovada` / `triagem_automatica_confianca`
- [x] Votos ponderados (1 / 3 / 3) com bloqueio de autovoto
- [x] Decisão do síndico para postagens em análise
- [x] Fechamento da janela de 24h (CronJob) e recálculo do trust_score
- [x] Testes de integração contra os procedures reais do PostgreSQL
- [ ] Rodar o CLIP de verdade (`pytest -m modelo`) e o `docker build`
- [ ] Calibrar o limiar com fotos reais (`python -m src.calibracao`)
- [ ] Rebaixar "pessoa confiável" com histórico ruim (`sp_atualizar_trust_score` hoje só promove — ajuste no SQL)

## 💻 Pré-requisitos

Antes de começar, verifique se você atendeu aos seguintes requisitos:

- Você instalou o **Python 3.12+** (o atomic-agents 2.x exige).
- Você tem o **Docker** para o PostgreSQL de teste e para a imagem de produção.
- Funciona em Windows, Linux e macOS (só CPU, sem GPU).
- Você tem acesso ao PostgreSQL do app com o schema do EcoCiente (`tests/sql/ecociente_schema.sql`) e os lookups carregados (modelo em `tests/sql/seed_lookups.sql`).

## 🚀 Instalando o Validador de Postagens

Linux e macOS:

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt
cp .env.example .env
```

Windows (Git Bash):

```bash
python -m venv .venv && source .venv/Scripts/activate
pip install -r requirements-dev.txt
cp .env.example .env
```

## ☕ Usando o Validador de Postagens

Suba a API (a 1ª vez baixa o CLIP, ~600 MB):

```bash
uvicorn src.api.app:criar_app --factory --reload
```

Documentação interativa: <http://localhost:8000/docs>

Rode os testes:

```bash
pytest                            # unitários, sem modelo (segundos)
pytest -m modelo                  # carrega o CLIP de verdade
pytest -m banco tests/validacao   # integração com PostgreSQL de verdade (precisa de URL_BANCO_TESTE)
```

Os testes `banco` **dão `DROP SCHEMA public CASCADE`** a cada teste: use um banco descartável, nunca o do app (sem `URL_BANCO_TESTE`, ou com host que não seja `localhost`/`127.0.0.1`, eles são pulados).

```bash
docker run -d --name ecociente-pg-teste -e POSTGRES_PASSWORD=teste -p 55432:5432 postgres:16
URL_BANCO_TESTE=postgresql://postgres:teste@localhost:55432/postgres pytest -m banco tests/validacao
```

### Estrutura

```text
src/
  triagem/     categorias · schemas · pertinencia (a regra) · foto (download seguro) · classificador (CLIP) · cabeca (cabeça treinada) · hash_perceptual · sinais · tool (ValidarFotoTool)
  validacao/   banco (PostgreSQL: triagem, votos, decisão, trust scores) · fechamento (job das janelas de 24h)
  api/         app (FastAPI) · metricas (Prometheus)
  agentes/     contexto (TriagensFotosCtx) · explicador · segunda_opiniao (modelo de visão e linguagem)
  calibracao/  python -m src.calibracao (limiar) · python -m src.calibracao.treinar (cabeça)
tests/         espelha as pastas acima
```

### Contrato HTTP

Todas as rotas exigem o header `X-Api-Key`, levam o id da postagem na URL e são chamadas só pelo backend do app.

**`POST /v1/postagens/{id}/triagem`** — sem corpo. Lê `url_foto` e a categoria da postagem, baixa a foto, roda o CLIP e grava `triagem_automatica_aprovada` / `triagem_automatica_confianca`. Chame depois do `INSERT` da postagem, com timeout de ~30 s. A conexão com o banco não fica aberta durante o download e a inferência; a URL nunca vai para o log.

```json
{"status": "ok", "pertinente": false, "categoria_informada": "plastico", "categoria_detectada": "vidro", "confianca": 87.42,
 "hash_foto": "f0e4c2a1b3d59687", "alternativas": [{"categoria": "plastico", "confianca": 9.1}, {"categoria": "metal", "confianca": 2.0}],
 "mensagem": "A foto parece conter vidro, não plástico."}
```

- `pertinente` é tri-estado: `null` = sem veredito automático (só com `LIMIAR_REVISAO` ligado); a API grava `NULL`.
- `sinais` (`padrao_de_tela`, `sem_exif`) são informativos e não mudam o veredito; ainda não foram calibrados com fotos reais.
- `hash_foto` é um hash perceptual (dHash); a duplicata exata é barrada pelo `UNIQUE` de `tb_postagens.hash_foto` (SHA-256 do backend).
- Categorias aceitas: papel, plastico, vidro, metal, organico (acento, maiúsculas e espaços são ignorados).

**`POST /v1/postagens/{id}/votos`** — `{"usuario_id": 7, "tipo": "aprovar", "motivo_denuncia_id": null, "comentario": null}`. `tipo` é `aprovar` ou `denunciar` (denúncia exige `motivo_denuncia_id`). Chama `sp_processar_voto_postagem` e responde `{"saldo_confianca": 3, "pontuacao_ativa": true}`.

**`POST /v1/postagens/{id}/decisao`** — `{"usuario_id": 1, "aprovar": true}`. Só o síndico do condomínio da postagem decide postagens em análise (`sp_decidir_postagem_analise`); depois a API atualiza os trust scores dos envolvidos. Responde 204.

| Status | Quando | O que o backend faz |
|---|---|---|
| 200 / 204 | triagem e voto / decisão feitos (arquivo que não é imagem é 200 com `pertinente: false`) | mostra `mensagem` (triagem) ou o saldo (voto) |
| 401 | `X-Api-Key` errada ou ausente | erro de configuração |
| 403 | autor votando na própria postagem; quem decide não é o síndico | mostra o erro ao usuário |
| 404 | postagem inexistente | erro de integração |
| 409 | usuário já votou nesta postagem | ignora; o voto anterior vale |
| 422 | host fora de `HOSTS_PERMITIDOS`, categoria fora do catálogo, corpo inválido, motivo inexistente ou regra recusada pelo procedure (`detail` traz a mensagem) | nada é gravado; registre no log |
| 502 | o storage não entregou a foto | tenta de novo mais tarde, poucas vezes |
| 503 | fila de triagens cheia ou banco indisponível (`Retry-After: 5`) | tenta de novo depois do intervalo |

### Variáveis de ambiente

| Variável | Padrão | Para quê |
|---|---|---|
| `CHAVE_API` | obrigatória na API (≥ 16 caracteres) | valor esperado no header `X-Api-Key` |
| `URL_BANCO` | obrigatória na API e no job | URL do PostgreSQL do app (`postgresql://usuario:senha@host:5432/banco`) |
| `HOSTS_PERMITIDOS` | obrigatória | hosts do storage das fotos, separados por vírgula (anti-SSRF) |
| `LIMIAR_CONFIANCA` | `0.5` | probabilidade mínima da categoria para aprovar |
| `LIMIAR_REVISAO` | desligado | se definido (ex.: `0.3`), probabilidade entre ele e o limiar vira `pertinente: null` |
| `LIMIARES_POR_CATEGORIA` | `{}` | limiar por categoria, em JSON: `{"vidro": 0.6}` |
| `MAX_PEDIDOS_SIMULTANEOS` | `8` | triagens em andamento; acima disso, 503 |
| `CABECA_TREINADA` | desligada | `.npz` do treino; troca o zero-shot pela cabeça treinada |
| `CHAVE_API_ANTERIOR` | desligada | chave antiga aceita durante a rotação; remova depois |
| `TAMANHO_MAXIMO_BYTES` | `10000000` | tamanho máximo da foto |
| `TIMEOUT_DOWNLOAD_SEGUNDOS` | `10` | timeout do download no storage |
| `MODELO_CLIP` | `openai/clip-vit-base-patch32` | precisa ser um CLIP; no Docker, `--build-arg MODELO_CLIP=...` |

### Deploy (k3s)

```bash
docker build -t ecociente-validador-fotos .
```

- O CLIP vai dentro da imagem (`HF_HUB_OFFLINE=1`): o pod sobe sem internet. Só CPU.
- Porta 8000; readiness e liveness em `GET /saude` (dê folga no `initialDelaySeconds`: o modelo carrega antes de abrir a porta).
- 1 réplica e 1 worker: cada processo carrega o modelo inteiro na RAM. Use ~1,5× o `docker stats` como `limits.memory`.
- `CHAVE_API` e `URL_BANCO` no Secret `validador-fotos`; gere a chave com `python -c "import secrets; print(secrets.token_urlsafe(32))"`.
- Service `ClusterIP`: só os serviços do cluster chamam.
- Fechamento das janelas de 24h: CronJob com `python -m src.validacao.fechamento` (não carrega o CLIP).

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: fechamento-janelas
spec:
  schedule: "*/5 * * * *"
  concurrencyPolicy: Forbid
  jobTemplate:
    spec:
      backoffLimit: 0   # o próximo agendamento já tenta de novo
      template:
        spec:
          restartPolicy: Never
          containers:
            - name: fechamento
              image: ecociente-validador-fotos
              command: ["python", "-m", "src.validacao.fechamento"]
              env:
                - name: URL_BANCO
                  valueFrom:
                    secretKeyRef: {name: validador-fotos, key: URL_BANCO}
```

### Observabilidade

- `GET /metrics` (Prometheus, sem chave; exponha só dentro do cluster): `validacoes_total{categoria,status,pertinente}`, `validacao_segundos`, `confianca_modelo{categoria}` e `pedidos_rejeitados_total`. Queda da confiança média sem mudança de modelo = mudou o tipo de foto que chega.
- Cada veredito sai no log como uma linha JSON com `id_postagem` e sem a URL; junto com a correção do moderador, vira o dataset de calibração.
- Rotação de chave: suba `CHAVE_API` nova e `CHAVE_API_ANTERIOR` antiga, troque nos chamadores, depois remova a anterior.

### Calibração e cabeça treinada

Fotos reais em `amostras/<categoria>/` (papel, plastico, vidro, metal, organico) e `amostras/nao_residuo/`, ~20 por pasta, tiradas com celular:

```bash
python -m src.calibracao amostras                   # tabela geral e por categoria
python -m src.calibracao.treinar amostras cabeca.npz  # cabeça treinada vs zero-shot na mesma fatia
```

Use em `LIMIAR_CONFIANCA` o menor limiar com `aprovaria_errada` ≤ 5%. Só ligue `CABECA_TREINADA` se a cabeça vencer o zero-shot com folga; ela guarda o CLIP com que foi treinada e o serviço recusa subir com outro `MODELO_CLIP`.

### Usar como tool do Atomic Agents

```python
from src.agentes.contexto import TriagensFotosCtx
from src.triagem.schemas import ValidarFotoInput
from src.triagem.tool import ValidarFotoConfig, ValidarFotoTool

ferramenta = ValidarFotoTool(ValidarFotoConfig())   # lê HOSTS_PERMITIDOS etc. do ambiente e carrega o CLIP
triagens = TriagensFotosCtx()                        # um por conversa
agente.register_context_provider("triagens_fotos", triagens)

resultado = ferramenta.run(ValidarFotoInput(url_foto=url, categoria="Plástico"))
triagens.registrar(resultado)
```

A tool não levanta exceção por falha de rotina: confira `resultado.status` (`ok`, `pedido_invalido`, `foto_indisponivel`). A **segunda opinião** (`SegundaOpiniaoVisao`, modelo de visão e linguagem para fotos na zona de revisão) é opcional e não vem ligada na API: custa uma chamada paga por foto incerta.

## 📫 Contribuindo para o Validador de Postagens

Para contribuir com o Validador de Postagens, siga estas etapas:

1. Clone este repositório.
2. Crie um branch: `git checkout -b <nome_branch>`.
3. Faça suas alterações e confirme-as: `git commit -m '<mensagem_commit>'`
4. Envie para o branch: `git push origin <nome_branch>`
5. Crie a solicitação de pull (o PR Bot gera a descrição automaticamente).

Como alternativa, consulte a documentação do GitHub em [como criar uma solicitação pull](https://help.github.com/en/github/collaborating-with-issues-and-pull-requests/creating-a-pull-request).

## 🤝 Colaboradores

Agradecemos às seguintes pessoas que contribuíram para este projeto:

<table>
  <tr>
    <td align="center">
      <a href="https://github.com/shinitihm" title="Perfil do Heitor no GitHub">
        <img src="https://avatars.githubusercontent.com/u/187130131?v=4" width="100px;" alt="Foto do Heitor Shiniti Miasato no GitHub"/><br>
        <sub>
          <b>Heitor Shiniti Miasato</b>
        </sub>
      </a>
    </td>
  </tr>
</table>

## 📝 Licença

Esse projeto está sob licença MIT. Veja o arquivo [LICENSE](LICENSE) para mais detalhes.
