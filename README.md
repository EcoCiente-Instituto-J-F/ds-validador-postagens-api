# Validador de Postagens — EcoCiente

![GitHub repo size](https://img.shields.io/github/repo-size/EcoCiente-Instituto-J-F/ds-validador-postagens-api?style=for-the-badge)
![GitHub language count](https://img.shields.io/github/languages/count/EcoCiente-Instituto-J-F/ds-validador-postagens-api?style=for-the-badge)
![GitHub forks](https://img.shields.io/github/forks/EcoCiente-Instituto-J-F/ds-validador-postagens-api?style=for-the-badge)
![GitHub open issues](https://img.shields.io/github/issues/EcoCiente-Instituto-J-F/ds-validador-postagens-api?style=for-the-badge)
![GitHub open pull requests](https://img.shields.io/github/issues-pr/EcoCiente-Instituto-J-F/ds-validador-postagens-api?style=for-the-badge)

> API de validação das postagens de reciclagem do EcoCiente, em Java 21 e Spring Boot. Faz a triagem automática da foto com um modelo de visão (CLIP em ONNX Runtime, só CPU), registra os votos ponderados da comunidade e a decisão do síndico, e fecha a janela de validação de 24h, tudo sobre o PostgreSQL do app. A pontuação e o ranking ficam com a API de Pontuação, que lê `pontuacao_reconciliacao_pendente`.

A postagem precisa de condomínio: usuário comum (sem condomínio) não posta foto. A triagem é um sinal auxiliar; quem decide é a votação comunitária. As regras de voto, histerese (-5 / ≥ 0), fechamento e trust_score vivem nos procedures do banco; a API só os chama.

## Ajustes e melhorias

O projeto ainda está em desenvolvimento e as próximas atualizações serão voltadas para as seguintes tarefas:

- [x] Triagem automática com CLIP gravando `triagem_automatica_aprovada` / `triagem_automatica_confianca`
- [x] Votos ponderados (1 / 3 / 3) com bloqueio de autovoto
- [x] Decisão do síndico para postagens em análise
- [x] Fechamento da janela de 24h (job agendado na própria API) e recálculo do trust_score
- [x] Migração de Python para Java 21 + Spring Boot, com o mesmo contrato HTTP
- [x] Dockerfile com os modelos embutidos na imagem
- [x] DHash conferido contra o algoritmo do Pillow (hashes gravados continuam comparáveis)
- [ ] Rodar a imagem Docker e o teste de integração com PostgreSQL (`./gradlew testeBanco`, precisa de Docker)
- [ ] Calibrar o limiar com fotos reais (`./gradlew calibrar`)
- [ ] Suporte a HEIC (fotos de iPhone): o Java não decodifica HEIC hoje
- [ ] Rebaixar "pessoa confiável" com histórico ruim (`sp_atualizar_trust_score` hoje só promove — ajuste no SQL)

## 💻 Pré-requisitos

Antes de começar, verifique se você atendeu aos seguintes requisitos:

- JDK 17 a 24 (recomendado: 21) para rodar o Gradle wrapper — o Gradle 8.14 não roda em Java 25+: o 1º build baixa sozinho o Gradle 8.14.3 e o **JDK 21** que compila o projeto (precisa de internet).
- Você tem o **Docker** para o PostgreSQL de teste, para os testes de integração e para a imagem de produção.
- Funciona em Windows, Linux e macOS (só CPU, sem GPU).
- Você tem acesso ao PostgreSQL do app com o schema do EcoCiente (`src/test/resources/sql/ecociente_schema.sql`) e os lookups carregados (`src/test/resources/sql/seed_lookups.sql`).
- Você baixou os arquivos do CLIP (ver [Modelo de visão](#modelo-de-visão-clip)).

## 🚀 Instalando o Validador de Postagens

Linux e macOS:

```bash
cp .env.example .env
./gradlew build
```

Windows (PowerShell ou Prompt de Comando):

```bat
copy .env.example .env
gradlew.bat build
```

### Modelo de visão (CLIP)

A API precisa de três arquivos do CLIP ViT-B/32 em ONNX, na pasta indicada por `CLIP_DIR` (padrão `modelos/clip-vit-base-patch32`):

| Arquivo | Tamanho aproximado | Origem |
|---|---|---|
| `vision_model.onnx` | 352 MB | [Xenova/clip-vit-base-patch32](https://huggingface.co/Xenova/clip-vit-base-patch32/tree/main/onnx) |
| `text_model.onnx` | 254 MB | idem |
| `tokenizer.json` | 2,2 MB | [Xenova/clip-vit-base-patch32](https://huggingface.co/Xenova/clip-vit-base-patch32/tree/main) |

```bash
mkdir -p modelos/clip-vit-base-patch32 && cd modelos/clip-vit-base-patch32
B=https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/main
curl -fL -o vision_model.onnx "$B/onnx/vision_model.onnx"
curl -fL -o text_model.onnx "$B/onnx/text_model.onnx"
curl -fL -o tokenizer.json "$B/tokenizer.json"
```

Confira os tamanhos: um arquivo truncado carrega até o meio e falha só na primeira foto.

## ☕ Usando o Validador de Postagens

Suba a API (a 1ª subida carrega o CLIP, leva alguns segundos):

```bash
./gradlew bootRun
```

Documentação interativa: <http://localhost:8000/docs>

Rode os testes:

```bash
./gradlew test                  # unitários, sem modelo nem banco (segundos)
./gradlew testeModelo           # carrega o CLIP de verdade (precisa de CLIP_DIR)
./gradlew testeBanco            # integração com PostgreSQL via Testcontainers (precisa de Docker)
```

O teste `testeBanco` aplica o schema inteiro num container descartável; nunca aponta para o banco do app.

### Estrutura

```text
src/main/java/br/com/ecociente/validador/
  core/          domínio puro: domain (records, catálogo) · service (triagem, votos, decisão) · gateway (portas) · imagem (dHash, FFT, reamostragem) · exception
  dataprovider/ adaptadores: gateway (JDBC, procedures) · foto (download seguro, decodificação) · visao (CLIP ONNX, tokenizer, cabeça treinada) · llm (segunda opinião)
  entrypoint/    controller · dto · exception (contrato HTTP) · metricas (Prometheus)
  config/        propriedades · segurança (X-Api-Key) · visão (carrega o CLIP) · job (fechamento das janelas)
  agentes/       explicador ao morador e contexto das triagens (fora da API)
  calibracao/    calibração do limiar e treino da cabeça (ferramentas de linha de comando)
src/test/        espelha as pastas acima; "modelo" e "banco" ficam fora do ./gradlew test
```

### Contrato HTTP

Todas as rotas de serviço exigem o header `X-Api-Key`, levam o id da postagem na URL e são chamadas só pelo backend do app. O corpo e as respostas são em snake_case.

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

Todos os erros têm o corpo `{"detail": "..."}`.

### Variáveis de ambiente

| Variável | Padrão | Para quê |
|---|---|---|
| `CHAVE_API` | obrigatória (≥ 16 caracteres) | valor esperado no header `X-Api-Key` |
| `DB_URL` | obrigatória | URL JDBC do PostgreSQL do app (`jdbc:postgresql://host:5432/banco`) |
| `DB_USER` / `DB_PASSWORD` | obrigatórias | credenciais do banco |
| `HOSTS_PERMITIDOS` | obrigatória | hosts do storage das fotos, separados por vírgula (anti-SSRF) |
| `CLIP_DIR` | `modelos/clip-vit-base-patch32` | pasta com `vision_model.onnx`, `text_model.onnx` e `tokenizer.json` |
| `LIMIAR_CONFIANCA` | `0.5` | probabilidade mínima da categoria para aprovar |
| `LIMIAR_REVISAO` | desligado | se definido (ex.: `0.3`), probabilidade entre ele e o limiar vira `pertinente: null` |
| `LIMIARES_POR_CATEGORIA` | vazio | limiar por categoria, em JSON: `{"vidro": 0.6}` |
| `MAX_PEDIDOS_SIMULTANEOS` | `8` | triagens em andamento; acima disso, 503 |
| `CABECA_TREINADA` | desligada | arquivo JSON gerado por `./gradlew treinarCabeca`; troca o zero-shot pela cabeça treinada |
| `CHAVE_API_ANTERIOR` | desligada | chave antiga aceita durante a rotação; remova depois |
| `TAMANHO_MAXIMO_BYTES` | `10000000` | tamanho máximo da foto |
| `TIMEOUT_DOWNLOAD_SEGUNDOS` | `10` | timeout do download no storage |
| `FECHAMENTO_HABILITADO` | `true` | liga o job que encerra as janelas de 24h |
| `SEGUNDA_OPINIAO_ATIVA` | `false` | liga a segunda opinião de um modelo de visão e linguagem (OpenAI-compatível) |
| `SERVER_PORT` | `8000` | porta HTTP |

> Migração: o serviço Python usava `URL_BANCO` (formato `postgresql://`) e `MODELO_CLIP` (id do Hugging Face). Na versão Java, use `DB_URL`/`DB_USER`/`DB_PASSWORD` e `CLIP_DIR`, e atualize o Secret do k8s.

### Deploy (k3s)

```bash
docker build -t ecociente-validador-fotos .
```

- Os arquivos do CLIP vão dentro da imagem (baixados e conferidos no build): o pod sobe sem internet. Só CPU.
- Porta 8000; readiness e liveness em `GET /saude` (dê folga no `initialDelaySeconds`: o modelo carrega antes de abrir a porta).
- 1 réplica: o job de fechamento roda dentro da API e cada processo carrega o modelo inteiro na RAM. A JVM usa até 75% da memória do contêiner (`JAVA_TOOL_OPTIONS`); ajuste o `limits.memory` pelo consumo medido.
- `CHAVE_API` e `DB_*` no Secret `validador-fotos`; gere a chave com `openssl rand -base64 32`.
- Service `ClusterIP`: só os serviços do cluster chamam.
- O CronJob de fechamento das janelas não é mais necessário: o job roda dentro da API (desligue com `FECHAMENTO_HABILITADO=false` se preferir outro arranjo).

### Observabilidade

- `GET /metrics` (Prometheus, sem chave; exponha só dentro do cluster): `validacoes_total{categoria,status,pertinente}`, `validacao_segundos`, `confianca_modelo{categoria}` e `pedidos_rejeitados_total`. Queda da confiança média sem mudança de modelo = mudou o tipo de foto que chega.
- Cada veredito sai no log como uma linha JSON com `id_postagem` e sem a URL; junto com a correção do moderador, vira o dataset de calibração.
- Rotação de chave: suba `CHAVE_API` nova e `CHAVE_API_ANTERIOR` antiga, troque nos chamadores, depois remova a anterior.

### Calibração e cabeça treinada

Fotos reais em `amostras/<categoria>/` (papel, plastico, vidro, metal, organico) e `amostras/nao_residuo/`, ~20 por pasta, tiradas com celular:

```bash
./gradlew calibrar -PpastaAmostras=amostras                        # tabela geral e por categoria
./gradlew treinarCabeca -PpastaAmostras=amostras -PsaidaCabeca=cabeca.json   # cabeça treinada vs zero-shot
```

Use em `LIMIAR_CONFIANCA` o menor limiar com `aprovaria_errada` ≤ 5%. Só ligue `CABECA_TREINADA` se a cabeça vencer o zero-shot com folga; o arquivo guarda o CLIP com que foi treinada e o serviço recusa subir com outro modelo.

### Usar a triagem como ferramenta de um agente

A triagem é um serviço Java; para um agente (LLM) use o `ExplicadorMorador` e o `ContextoTriagens` do pacote `agentes`, que recebem um `ClienteChatOpenAi` (compatível com `/chat/completions`). A API não depende de LLM: a segunda opinião e o explicador são opcionais.

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
