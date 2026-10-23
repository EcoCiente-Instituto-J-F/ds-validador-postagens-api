# Política de Segurança

## Dados sensíveis

Este projeto trata de:

- **URLs de fotos** de resíduos enviadas por moradores (protegidas contra SSRF com `HOSTS_PERMITIDOS`)
- **Credenciais de acesso ao PostgreSQL** (variáveis de ambiente, nunca commitadas)
- **Chave de autenticação X-Api-Key** (Secret no k3s, gerada com `openssl rand -base64 32`)
- **Dados de usuários e condomínios** (persistidos em banco de dados conforme o schema EcoCiente)
- **Métricas e logs** contendo `id_postagem` (URLs e IPs jamais nos logs)

## Versões suportadas

| Versão | Suportada |
|---|---|
| 2.0.0 | ✅ Sim |
| < 2.0.0 | ❌ Não |

Apenas a versão mais recente (`main` branch) recebe correções de segurança. Se você está usando uma versão anterior, considere atualizar.

## Reportando uma vulnerabilidade

**Não abra issue pública, pull request ou discussão para reportar uma vulnerabilidade.** Isso divulga o problema antes que possamos corrigi-lo.

### Passos

1. Acesse <https://github.com/EcoCiente-Instituto-J-F/ds-validador-postagens-api/security/advisories/new>

2. Clique em **Report a vulnerability**

3. Preencha o formulário com:
   - **Título**: resumo da vulnerabilidade
   - **Descrição**: impacto e passos de reprodução (use dados fictícios, ex.: usuário ID 999)
   - **Versão afetada**: qual versão do projeto
   - **Commit testado**: hash ou branch onde reproduziu
   - **Proposta de correção**: sua ideia (se souber)

4. No repositório, ative **Private vulnerability reporting** em:
   > Settings → Code security and analysis → Private vulnerability reporting (✓ Enable)

### O que acontece depois

1. **Confirmação**: receberá uma resposta dentro de 7 dias
2. **Correção privada**: trabalharemos em uma branch privada; você pode acompanhar o progresso
3. **Publicação**: ao corrigir, publicamos um advisory no GitHub com seu crédito (a menos que prefira ficar anônimo)
4. **Prazo razoável**: solicitamos que aguarde pelo menos 90 dias antes de divulgar publicamente, para dar tempo aos usuários de atualizar

## Escopo

### Dentro do escopo (reportáveis)

- **Acesso a dados de outro usuário**: user A vendo fotos/votos/condomínio de user B
- **Burla de autenticação/autorização**: chamar endpoints sem X-Api-Key válida; síndico alterando postagens de outro condomínio
- **Injeção SQL/Cypher/comandos com exposição de dados**: query injection nos procedures; RCE em downloads
- **Vazamento de dados pessoais em logs/respostas**: IPs, emails, senhas em stack traces públicos
- **Segredos commitados**: CLIP_DIR, chaves, senhas no repo ou Dockerfile
- **Headers ou flags de teste aceitos fora de `APP_ENV=test`**: ex.: `X-Debug=true` habilitando logs extras em produção
- **SSRF**: bypass de `HOSTS_PERMITIDOS` para acessar serviços internos

### Fora do escopo (não reportáveis)

- Bugs de lógica sem impacto de segurança (ex.: cálculo de pontuação errado)
- Falhas em serviços de terceiros (Hugging Face, GitHub)
- Credenciais `CHANGEME` de desenvolvimento (esperado em `.env.example`)
- Negação de serviço (DoS) no próprio deploy (escalabilidade, não segurança)
- Problemas de infraestrutura k3s (fora do escopo do código)

## Como o sistema protege os dados

| Camada | Proteção |
|---|---|
| **Autenticação** | Header `X-Api-Key` validado em `br.com.ecociente.validador.config.FiltroChaveApi` para toda rota `/v1/` |
| **Autorização** | Síndico pode decidir apenas postagens do seu condomínio (validado em `sp_decidir_postagem_analise`) |
| **SSRF** | URLs de foto validadas contra `HOSTS_PERMITIDOS` em `br.com.ecociente.validador.dataprovider.foto.BaixadorFoto` |
| **Injeção SQL** | Procedures pré-compilados e paramétricos (JDBC prepared statements); nenhuma concatenação de strings |
| **Logs** | IDs e mensagens genéricas apenas; URLs, tokens e IPs jamais exportados (classe `br.com.ecociente.validador.entrypoint.exception.TratadorExcecoes`) |
| **Respostas HTTP** | Stack traces nunca expostos em produção; detalhes genéricos ao cliente em `application/json` com `detail: ...` |
| **Banco** | PostgreSQL com credenciais no Secret k3s; conexão JDBC em `DB_URL` com `sslmode=require` em produção |
| **Modelo** | CLIP em CPU apenas; sem acesso a internet após load; arquivo ONNX validado no Dockerfile |

## Checklist de deploy

Antes de subir em produção:

- [ ] `APP_ENV=production` (não expõe dados em logs nem aceita headers de debug)
- [ ] Segredos em Secret k3s, **nunca** em ConfigMap nem imagem Docker
  - [ ] `CHAVE_API` gerada: `openssl rand -base64 32`
  - [ ] `DB_USER` / `DB_PASSWORD` com role de banco de mínimo de permissão (só SELECT/INSERT/UPDATE/EXECUTE em procedures do app)
- [ ] Todo `CHANGEME` e placeholder substituído
- [ ] Não expor endpoints internos:
  - [ ] `/metrics` acessível apenas dentro do cluster (sem header `X-Api-Key` necessário; exponha via Service `ClusterIP`)
  - [ ] `/saude` acessível publicamente (readiness/liveness)
  - [ ] `/v1/` requer `X-Api-Key`
- [ ] CLIP_DIR com arquivos ONNX já carregados na imagem (verificados no Dockerfile)
- [ ] Liveness probe: `GET /saude` com `initialDelaySeconds` ≥ 15s (modelo carrega)

## Contato

Dúvidas sobre segurança? Envie e-mail para **ecociente.jef@gmail.com**.
