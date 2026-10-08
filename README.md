# Validador de Fotos — EcoCiente

Dada uma postagem do banco do app, baixa a foto, compara com a categoria escolhida pelo morador, roda um modelo de visão (CLIP zero-shot, só CPU) e responde se a foto é pertinente. Grava a triagem automática em `tb_postagens` (`triagem_automatica_aprovada` / `triagem_automatica_confianca`): auxiliar — a votação comunitária continua decidindo. Também registra votos e a decisão do síndico sobre a postagem.

O núcleo é a `ValidarFotoTool` (Atomic Agents). A API HTTP é um adaptador sobre ela e sobre o banco (`src/banco.py`); agentes podem usar a mesma tool direto.

## Estrutura

```
src/
  dominio/     categorias.py (catálogo e prompts) · schemas.py (entrada/saída da tool) · pertinencia.py (a regra)
  visao/       foto.py (download seguro) · classificador.py (CLIP) · cabeca.py (cabeça treinada) · hash_perceptual.py (dHash) · sinais.py (foto de tela)
  tools/       validar_foto_tool.py (ValidarFotoTool + ValidarFotoConfig)
  agentes/     contexto.py (TriagensFotosCtx) · explicador.py (explica o resultado ao morador) · segunda_opiniao.py (modelo de visão e linguagem)
  api/         app.py (FastAPI) · metricas.py (Prometheus)
  banco.py     acesso ao PostgreSQL do app (triagem, votos, decisão, trust scores)
  fechamento.py  job que fecha as janelas de validação vencidas (python -m src.fechamento)
  calibracao/  python -m src.calibracao (limiar) · python -m src.calibracao.treinar (cabeça)
tests/         espelha as pastas acima
```

## Rodar local

Precisa de Python 3.12+ (o atomic-agents 2.x exige).

```bash
python -m venv .venv && source .venv/Scripts/activate   # Linux/macOS: source .venv/bin/activate
pip install -r requirements-dev.txt
cp .env.example .env
uvicorn src.api.app:criar_app --factory --reload   # a 1ª vez baixa o CLIP (~600 MB)
```

Documentação interativa: http://localhost:8000/docs

## Testes

```bash
pytest             # unitários, sem modelo (segundos)
pytest -m modelo   # carrega o CLIP de verdade
pytest -m banco tests/banco   # integração com PostgreSQL de verdade (precisa de URL_BANCO_TESTE)
```

Os testes `banco` **dão `DROP SCHEMA public CASCADE`** a cada teste: use um banco descartável, nunca o do app (sem `URL_BANCO_TESTE`, ou com host que não seja `localhost`/`127.0.0.1`, eles são pulados).

```bash
docker run -d --name ecociente-pg-teste -e POSTGRES_PASSWORD=teste -p 55432:5432 postgres:16
URL_BANCO_TESTE=postgresql://postgres:teste@localhost:55432/postgres pytest -m banco tests/banco
```

## Contrato HTTP

Todas as rotas exigem o header `X-Api-Key` e levam o id da postagem na URL. A API lê e grava no PostgreSQL do app (`URL_BANCO`); quem chama é o backend do app.

### `POST /v1/postagens/{id}/triagem`

Sem corpo. A API lê `url_foto` e a categoria da postagem, baixa a foto, roda o CLIP e grava `triagem_automatica_aprovada` e `triagem_automatica_confianca` em `tb_postagens`. Chame depois do `INSERT` da postagem, com timeout de uns 30 s (download + inferência em CPU; pedidos simultâneos entram numa fila curta e, passando do limite, recebem 503). URLs assinadas (com `?X-Amz-Signature=...`) funcionam e a URL nunca vai para o log. A conexão com o banco não fica aberta durante o download e a inferência.

Resposta 200:

```json
{"status": "ok", "pertinente": false, "categoria_informada": "plastico", "categoria_detectada": "vidro", "confianca": 87.42,
 "hash_foto": "f0e4c2a1b3d59687", "alternativas": [{"categoria": "plastico", "confianca": 9.1}, {"categoria": "metal", "confianca": 2.0}],
 "mensagem": "A foto parece conter vidro, não plástico."}
```

- `pertinente` é tri-estado: `true`, `false` ou `null`. `null` = sem veredito automático (só aparece com `LIMIAR_REVISAO` ligado: a foto vai para revisão humana; a API grava `NULL` em `triagem_automatica_aprovada`).
- `sinais`: indícios **informativos** (não mudam o veredito): `padrao_de_tela` (pico de frequência de moiré, o mais forte) e `sem_exif` (fraco: apps de mensagem também removem EXIF). Não foram calibrados com fotos reais; textura regular de verdade (tecido, persiana) também dispara `padrao_de_tela`. Se usar, combine os dois e comece só registrando.
- `segunda_opiniao`: `true` quando o veredito veio do modelo de visão e linguagem (veja abaixo).
- `alternativas`: as próximas classes mais prováveis (até 2), úteis para mensagens do tipo "vi vidro e plástico".
- `hash_foto`: hash perceptual (16 hex). Para achar foto repetida, compare com os das postagens anteriores; distância de Hamming ≤ ~5 bits é a mesma foto (`src.visao.hash_perceptual.distancia`). O validador não guarda o hash.
- A categoria do banco precisa estar no catálogo do validador (papel, plastico, vidro, metal, organico; acento, maiúsculas e espaços são ignorados).

### `POST /v1/postagens/{id}/votos`

```json
{"usuario_id": 7, "tipo": "aprovar", "motivo_denuncia_id": null, "comentario": null}
```

`tipo` é `aprovar` ou `denunciar`; `motivo_denuncia_id` (para denúncia) e `comentario` (até 255 caracteres) são opcionais. Chama `sp_processar_voto_postagem` e responde 200 `{"saldo_confianca": 3, "pontuacao_ativa": true}`.

### `POST /v1/postagens/{id}/decisao`

```json
{"usuario_id": 1, "aprovar": true}
```

Só o síndico do condomínio da postagem decide (`sp_decidir_postagem_analise`); depois a API atualiza os trust scores dos envolvidos. Responde 204 sem corpo.

### Status

| Status | Quando | O que o backend faz |
|---|---|---|
| 200 / 204 | triagem e voto / decisão feitos (a triagem de arquivo que não é imagem ou grande demais é 200 com `pertinente: false`) | mostra `mensagem` ao morador (triagem) ou o saldo (voto) |
| 401 | `X-Api-Key` errada ou ausente | erro de configuração: não tenta de novo |
| 403 | autor votando na própria postagem; quem decide não é o síndico do condomínio | mostra o erro ao usuário |
| 404 | postagem inexistente | erro de integração |
| 409 | usuário já votou nesta postagem | ignora; o voto anterior vale |
| 422 | host da foto fora de `HOSTS_PERMITIDOS`, categoria fora do catálogo, corpo inválido, referência inválida (motivo de denúncia inexistente) ou regra recusada pelo procedure (`detail` traz a mensagem) | triagem: nada é gravado (fica `NULL` = não processada), registre no log |
| 502 | o storage não entregou a foto (fora do ar, timeout, 404) | nada é gravado; tenta de novo mais tarde, poucas vezes |
| 503 | mais de `MAX_PEDIDOS_SIMULTANEOS` triagens em andamento, ou banco de dados indisponível (header `Retry-After: 5` nos dois) | nada é gravado; tenta de novo depois do intervalo |

A API grava `triagem_automatica_*`. A reconciliação de pontos fica com a API de Pontuação, que consome `pontuacao_reconciliacao_pendente`.

## Usar como tool do Atomic Agents

```python
from src.agentes.contexto import TriagensFotosCtx
from src.dominio.schemas import ValidarFotoInput
from src.tools.validar_foto_tool import ValidarFotoConfig, ValidarFotoTool

ferramenta = ValidarFotoTool(ValidarFotoConfig())   # lê HOSTS_PERMITIDOS etc. do ambiente e carrega o CLIP
triagens = TriagensFotosCtx()
agente.register_context_provider("triagens_fotos", triagens)   # agente = seu AtomicAgent

resultado = ferramenta.run(ValidarFotoInput(url_foto=url, categoria="Plástico"))
triagens.registrar(resultado)   # o próximo agente.run() já vê a seção "Triagens recentes de fotos"
```

Para o agente que explica o resultado ao morador, veja `src/agentes/explicador.py` (`criar_explicador` e `triar_e_responder`): ele recebe um cliente Instructor do provedor que você escolher e lê as triagens pelo `TriagensFotosCtx`. Use **um `TriagensFotosCtx` por conversa**: se várias pessoas dividirem a instância, as triagens delas se misturam no prompt.

**Segunda opinião (opcional):** `SegundaOpiniaoVisao` (`agentes/segunda_opiniao.py`) manda a foto a um modelo de visão e linguagem só quando o veredito cai na zona de revisão (`LIMIAR_REVISAO`). Resposta sim/não vira `pertinente` e `segunda_opiniao: true`; se o provedor falhar, a foto segue para revisão humana. Cada chamada ao provedor tem timeout de 15 s (`timeout_segundos`, `None` para provedor que não aceite o parâmetro), porque segura uma vaga do limite de pedidos. O prompt manda ignorar texto escrito na foto, mas um modelo de visão ainda pode ser enganado por isso: a triagem é auxiliar e a votação comunitária continua decidindo. Não vem ligada na API: custa uma chamada paga por foto incerta e depende do provedor que você escolher. Para ligar, use um entrypoint seu:

```python
import instructor, openai   # ou anthropic, etc.
from src.agentes.segunda_opiniao import SegundaOpiniaoVisao
from src.api.app import Configuracoes, criar_app
from src.tools.validar_foto_tool import ValidarFotoTool

def criar():
    config = Configuracoes()
    opiniao = SegundaOpiniaoVisao(instructor.from_openai(openai.OpenAI()), "gpt-5-mini")
    return criar_app(config, ValidarFotoTool(config, segunda_opiniao=opiniao))
# uvicorn meu_modulo:criar --factory
```

A tool não levanta exceção por falha de rotina: confira `resultado.status` (`ok`, `pedido_invalido`, `foto_indisponivel`). Num agente roteador, `ValidarFotoInput` é o schema de chamada da tool; as `description` dos campos vão para o prompt.

## Variáveis de ambiente

| Variável | Padrão | Para quê |
|---|---|---|
| `CHAVE_API` | obrigatória na API (≥ 16 caracteres) | valor esperado no header `X-Api-Key` |
| `URL_BANCO` | obrigatória na API e no job de fechamento | URL do PostgreSQL do app (`postgresql://usuario:senha@host:5432/banco`) |
| `HOSTS_PERMITIDOS` | obrigatória | hosts do storage das fotos, separados por vírgula (anti-SSRF) |
| `LIMIAR_CONFIANCA` | `0.5` | probabilidade mínima da categoria para aprovar |
| `LIMIAR_REVISAO` | desligado | se definido (ex.: `0.3`), categoria certa com probabilidade entre ele e o limiar vira `pertinente: null` (revisão humana) em vez de recusada |
| `LIMIARES_POR_CATEGORIA` | `{}` | limiar próprio por categoria, em JSON: `{"vidro": 0.6, "metal": 0.55}`; as demais usam `LIMIAR_CONFIANCA` |
| `MAX_PEDIDOS_SIMULTANEOS` | `8` | pedidos em andamento; acima disso a API responde 503 em vez de enfileirar sem fim |
| `CABECA_TREINADA` | desligada | caminho do `.npz` gerado pelo treino; troca o zero-shot pela cabeça treinada (no k3s, monte via ConfigMap/volume; o arquivo tem poucos KB) |
| `CHAVE_API_ANTERIOR` | desligada | chave antiga, aceita junto com a nova durante a rotação (≥ 16 caracteres); remova depois |
| `TAMANHO_MAXIMO_BYTES` | `10000000` | tamanho máximo da foto |
| `TIMEOUT_DOWNLOAD_SEGUNDOS` | `10` | timeout do download no storage |
| `MODELO_CLIP` | `openai/clip-vit-base-patch32` | precisa ser um CLIP; no Docker, troque com `--build-arg MODELO_CLIP=...` (o modelo vai na imagem) |

## Deploy (k3s)

```bash
docker build -t ecociente-validador-fotos .
```

- O CLIP vai dentro da imagem (`HF_HUB_OFFLINE=1`): o pod sobe sem internet. Só CPU.
- Porta 8000. Readiness e liveness em `GET /saude`. O modelo carrega antes de o servidor abrir a porta: dê folga no `initialDelaySeconds` da liveness.
- 1 réplica e 1 worker: cada processo carrega o modelo inteiro na RAM.
- Memória: meça com `docker stats --no-stream` com o modelo carregado e use ~1,5× o valor como `limits.memory`.
- `CHAVE_API` e `URL_BANCO` no mesmo Secret (`validador-fotos`, o que o CronJob abaixo referencia); gere a chave com `python -c "import secrets; print(secrets.token_urlsafe(32))"`.
- Service `ClusterIP`: só os serviços do cluster chamam; não precisa de Ingress.
- Fechamento das janelas de validação (24h): um CronJob roda `python -m src.fechamento` (não carrega o CLIP).

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
              command: ["python", "-m", "src.fechamento"]
              env:
                - name: URL_BANCO
                  valueFrom:
                    secretKeyRef: {name: validador-fotos, key: URL_BANCO}
```


## Observabilidade

- `GET /metrics` (Prometheus, sem chave, como `/saude`: exponha só dentro do cluster): `validacoes_total{categoria,status,pertinente}`, `validacao_segundos`, `confianca_modelo{categoria}` e `pedidos_rejeitados_total` (503). A confiança média por categoria é o alerta de deriva: se cair sem mudança de modelo, mudou o tipo de foto que chega.
- Cada veredito sai no log como uma linha JSON (logger `src.veredito`, sem a URL, que pode carregar token de URL assinada). Junte essas linhas com a correção do moderador e você tem o dataset de calibração.
- **Rotação de chave:** suba o Secret com `CHAVE_API` nova e `CHAVE_API_ANTERIOR` antiga, troque a chave nos chamadores, depois remova a anterior.

## Calibração

Fotos reais em `amostras/<categoria>/` (papel, plastico, vidro, metal, organico) e `amostras/nao_residuo/` (selfies, pets, prints), ~20 por pasta, tiradas com celular. Depois:

```bash
python -m src.calibracao amostras
```

A saída traz a tabela geral e uma por categoria (use esta para `LIMIARES_POR_CATEGORIA`: vidro e plástico costumam se confundir mais que papel). `aprova_corretas` = % das fotos honestas que seriam aprovadas; `aprovaria_errada` = % das fotos que passariam como outra categoria. Use em `LIMIAR_CONFIANCA` o menor limiar com `aprovaria_errada` ≤ 5%.

## Cabeça treinada e comparação de modelos

Com as fotos de `amostras/` (mesmas pastas da calibração):

```bash
python -m src.calibracao.treinar amostras cabeca.npz
```

Separa 1 foto em cada 5 como teste, treina a cabeça (regressão logística sobre os embeddings do CLIP, só numpy em produção) e imprime a acurácia dela **e a do zero-shot na mesma fatia**. Só ligue `CABECA_TREINADA=cabeca.npz` se a cabeça passar o zero-shot com folga; o arquivo salvo é treinado com todas as fotos. O arquivo guarda com qual CLIP foi treinado: se `MODELO_CLIP` for outro, o serviço se recusa a subir (treine de novo).

Para comparar CLIPs (ex.: `openai/clip-vit-base-patch16`, `openai/clip-vit-large-patch14`): `python -m src.calibracao amostras <modelo>` e veja acurácia e `aprovaria_errada`; o modelo maior custa mais RAM e CPU por foto.

## O que não foi verificado

Este código foi escrito e testado num ambiente sem acesso ao Hugging Face, ao índice CPU do PyTorch e a provedores de LLM. Verificado: toda a lógica, a API, a cabeça (com embeddings sintéticos), os sinais (com imagens sintéticas) e os agentes (com cliente Instructor falso). **Não verificado:** carregar e rodar o CLIP de verdade (`pytest -m modelo` cobre classificação e `embedding`, e é o primeiro comando a rodar), `docker build`, memória do pod, qualidade das previsões, o limiar ideal, a eficácia dos sinais de foto de tela em fotos reais e uma chamada real à segunda opinião.

Ficou de fora de propósito: quantização/ONNX do modelo (só vale depois de medir latência real no cluster; a proteção contra sobrecarga é o limite de pedidos com 503) e rate limit por chave (há uma chave só, o backend do app).
