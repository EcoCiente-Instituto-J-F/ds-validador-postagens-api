# Validador de Fotos — EcoCiente

Recebe a URL da foto de uma postagem e a categoria escolhida pelo morador, roda um modelo de visão (CLIP zero-shot, só CPU) e responde se a foto é pertinente. É a triagem automática de `tb_postagens` (`triagem_automatica_aprovada` / `triagem_automatica_confianca`): auxiliar — a votação comunitária continua decidindo.

O núcleo é a `ValidarFotoTool` (Atomic Agents). A API HTTP é um adaptador fino sobre ela; agentes podem usar a mesma tool direto.

## Estrutura

```
validador_fotos/
  dominio/     categorias.py (catálogo e prompts) · schemas.py (entrada/saída da tool) · pertinencia.py (a regra)
  visao/       foto.py (download seguro) · classificador.py (CLIP) · hash_perceptual.py (dHash)
  tools/       validar_foto_tool.py (ValidarFotoTool + ValidarFotoConfig)
  agentes/     contexto.py (TriagensFotosCtx) · explicador.py (agente que explica o resultado ao morador)
  api/         app.py (FastAPI) · metricas.py (Prometheus)
  calibracao/  python -m validador_fotos.calibracao
tests/         espelha as pastas acima
```

## Rodar local

Precisa de Python 3.12+ (o atomic-agents 2.x exige).

```bash
python -m venv .venv && source .venv/Scripts/activate   # Linux/macOS: source .venv/bin/activate
pip install -r requirements-dev.txt
cp .env.example .env
uvicorn validador_fotos.api.app:criar_app --factory --reload   # a 1ª vez baixa o CLIP (~600 MB)
```

Documentação interativa: <http://localhost:8000/docs>

## Testes

```bash
pytest             # unitários, sem modelo (segundos)
pytest -m modelo   # carrega o CLIP de verdade
```

## Contrato HTTP

`POST /v1/validacoes` com o header `X-Api-Key`:

```json
{"url_foto": "https://<host do storage>/postagens/42.jpg", "categoria": "Plástico"}
```

`categoria` é o `nome_categoria` de `tb_lkp_categorias_residuos`; acento, maiúsculas e espaços são ignorados. Aceitas: papel, plastico, vidro, metal, organico.

Resposta 200:

```json
{"status": "ok", "pertinente": false, "categoria_informada": "plastico", "categoria_detectada": "vidro", "confianca": 87.42,
 "hash_foto": "f0e4c2a1b3d59687", "alternativas": [{"categoria": "plastico", "confianca": 9.1}, {"categoria": "metal", "confianca": 2.0}],
 "mensagem": "A foto parece conter vidro, não plástico."}
```

- `pertinente` é tri-estado: `true`, `false` ou `null`. `null` = sem veredito automático (só aparece com `LIMIAR_REVISAO` ligado: a foto vai para revisão humana; grave `NULL` em `triagem_automatica_aprovada`).
- `alternativas`: as próximas classes mais prováveis (até 2), úteis para mensagens do tipo "vi vidro e plástico".
- `hash_foto`: hash perceptual (16 hex). Para achar foto repetida, o app compara com os das postagens anteriores; distância de Hamming ≤ ~5 bits é a mesma foto (`validador_fotos.visao.hash_perceptual.distancia`). O validador não guarda nada: quem tem o histórico é o banco do app.

| Status | Quando | O que o chamador faz |
|---|---|---|
| 200 | veredito — inclusive arquivo que não é imagem ou grande demais (`pertinente: false`, `categoria_detectada`, `confianca` e `hash_foto` nulos) | grava `triagem_automatica_aprovada = pertinente` e `triagem_automatica_confianca = confianca`; mostra `mensagem` ao morador |
| 401 | `X-Api-Key` errada ou ausente | erro de configuração: não grava nada |
| 422 | categoria fora do catálogo, URL malformada ou host fora de `HOSTS_PERMITIDOS` | erro de integração: não grava (fica `NULL` = não processada) e registra no log |
| 502 | o storage não entregou a foto (fora do ar, timeout, 404) | não grava; tenta de novo mais tarde, poucas vezes |

Chame depois do `INSERT` da postagem, fora da transação, com timeout de uns 30 s (download + inferência em CPU; pedidos simultâneos entram em fila).

## Usar como tool do Atomic Agents

```python
from validador_fotos.agentes.contexto import TriagensFotosCtx
from validador_fotos.dominio.schemas import ValidarFotoInput
from validador_fotos.tools.validar_foto_tool import ValidarFotoConfig, ValidarFotoTool

ferramenta = ValidarFotoTool(ValidarFotoConfig())   # lê HOSTS_PERMITIDOS etc. do ambiente e carrega o CLIP
triagens = TriagensFotosCtx()
agente.register_context_provider("triagens_fotos", triagens)   # agente = seu AtomicAgent

resultado = ferramenta.run(ValidarFotoInput(url_foto=url, categoria="Plástico"))
triagens.registrar(resultado)   # o próximo agente.run() já vê a seção "Triagens recentes de fotos"
```

Para o agente que explica o resultado ao morador, veja `validador_fotos/agentes/explicador.py` (`criar_explicador` e `triar_e_responder`): ele recebe um cliente Instructor do provedor que você escolher e lê as triagens pelo `TriagensFotosCtx`. Use **um `TriagensFotosCtx` por conversa**: se várias pessoas dividirem a instância, as triagens delas se misturam no prompt.

A tool não levanta exceção por falha de rotina: confira `resultado.status` (`ok`, `pedido_invalido`, `foto_indisponivel`). Num agente roteador, `ValidarFotoInput` é o schema de chamada da tool; as `description` dos campos vão para o prompt.

## Variáveis de ambiente

| Variável | Padrão | Para quê |
|---|---|---|
| `CHAVE_API` | obrigatória na API (≥ 16 caracteres) | valor esperado no header `X-Api-Key` |
| `HOSTS_PERMITIDOS` | obrigatória | hosts do storage das fotos, separados por vírgula (anti-SSRF) |
| `LIMIAR_CONFIANCA` | `0.5` | probabilidade mínima da categoria para aprovar |
| `LIMIAR_REVISAO` | desligado | se definido (ex.: `0.3`), categoria certa com probabilidade entre ele e o limiar vira `pertinente: null` (revisão humana) em vez de recusada |
| `LIMIARES_POR_CATEGORIA` | `{}` | limiar próprio por categoria, em JSON: `{"vidro": 0.6, "metal": 0.55}`; as demais usam `LIMIAR_CONFIANCA` |
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
- `CHAVE_API` num Secret; gere com `python -c "import secrets; print(secrets.token_urlsafe(32))"`.
- Service `ClusterIP`: só os serviços do cluster chamam; não precisa de Ingress.

## Observabilidade

- `GET /metrics` (Prometheus, sem chave, como `/saude`: exponha só dentro do cluster): `validacoes_total{categoria,status,pertinente}`, `validacao_segundos` e `confianca_modelo{categoria}`. A confiança média por categoria é o alerta de deriva: se cair sem mudança de modelo, mudou o tipo de foto que chega.
- Cada veredito sai no log como uma linha JSON (logger `validador_fotos.veredito`, sem a URL, que pode carregar token de URL assinada). Junte essas linhas com a correção do moderador e você tem o dataset de calibração.
- **Rotação de chave:** suba o Secret com `CHAVE_API` nova e `CHAVE_API_ANTERIOR` antiga, troque a chave nos chamadores, depois remova a anterior.

## Calibração

Fotos reais em `amostras/<categoria>/` (papel, plastico, vidro, metal, organico) e `amostras/nao_residuo/` (selfies, pets, prints), ~20 por pasta, tiradas com celular. Depois:

```bash
python -m validador_fotos.calibracao amostras
```

A saída traz a tabela geral e uma por categoria (use esta para `LIMIARES_POR_CATEGORIA`: vidro e plástico costumam se confundir mais que papel). `aprova_corretas` = % das fotos honestas que seriam aprovadas; `aprovaria_errada` = % das fotos que passariam como outra categoria. Use em `LIMIAR_CONFIANCA` o menor limiar com `aprovaria_errada` ≤ 5%.

## Fora desta versão (e por quê)

Precisam de dados reais ou infraestrutura que não existiam quando isto foi escrito:

- **Cabeça treinada sobre embeddings do CLIP e CLIP maior (ViT-B/16, L/14):** só vale medir com fotos rotuladas do app (use o log de vereditos + a correção do moderador). `MODELO_CLIP` já aceita outro CLIP.
- **Segunda opinião de um modelo de visão e linguagem nos casos incertos:** exige provedor, chave e custo por chamada; faça depois de ver quantas fotos caem na zona de revisão.
- **Detecção de foto de tela (moiré/EXIF):** EXIF ausente é comum em fotos legítimas (WhatsApp remove), então o sinal sozinho gera falso positivo. Precisa de fotos reais para ajustar.
- **Vazão (ONNX/quantização, batch, fila assíncrona):** hoje 1 pod, 1 worker, inferência serializada. Meça latência no cluster antes; o primeiro passo é uma réplica a mais.
- **Rate limit:** há uma chave só (o backend do app), então limitar por chave não protege nada; faça no Ingress/NetworkPolicy se um dia o serviço sair do cluster.
