# Validador de Fotos de Reciclagem (EcoCiente) — Plano de Implementação

> **Para agentes:** SUB-SKILL OBRIGATÓRIA: use superpowers:subagent-driven-development (recomendado) ou superpowers:executing-plans para implementar este plano task a task. Os passos usam checkbox (`- [ ]`) para acompanhamento.

**Goal:** Microsserviço que recebe a URL da foto de uma postagem e a categoria escolhida pelo morador, passa a foto por um modelo de visão e responde se ela é pertinente, com confiança (0–100) e uma mensagem para o app.

**Architecture:** API FastAPI sem estado, rodando como mais um pod no k3s. A cada pedido: baixa a foto só de hosts permitidos (anti-SSRF) → normaliza a imagem (RGB, orientação EXIF, HEIC, limites de tamanho) → o `Classificador` devolve a tupla `(classe_detectada, probabilidade)` → `decidir_pertinencia` transforma a tupla no veredito. O modelo padrão é o CLIP em modo zero-shot (sem dataset, sem treino, só CPU), atrás de um `Protocol`: um modelo treinado pela equipe entra no lugar sem mexer no resto.

**Tech Stack:** Python 3.12 · FastAPI + Uvicorn · httpx · Pillow + pillow-heif · transformers + torch (CPU) com `openai/clip-vit-base-patch32` · pydantic-settings · pytest · Docker

**Spec:** não há documento separado — a spec vai na seção **Spec (resumo)** logo abaixo. Fontes: o pedido de 01/10/2026 (receber a URL da foto e a classificação → ML devolve uma tupla → API diz se está pertinente) e o `ecociente_schema.sql` (`tb_postagens.triagem_automatica_aprovada` / `triagem_automatica_confianca`, `tb_lkp_categorias_residuos`).

## Spec (resumo)

**Fluxo no produto:** o morador tira a foto no app → escolhe a categoria → o serviço que cria a postagem chama este validador com `url_foto` + `categoria` → o validador baixa a foto e roda o modelo, que devolve a tupla `(classe_detectada, probabilidade)` → o validador responde se a foto é pertinente → o chamador grava `triagem_automatica_aprovada` / `triagem_automatica_confianca` em `tb_postagens` e mostra a `mensagem` ao morador.

**Requisitos**

1. `POST /v1/validacoes` recebe `url_foto` (string) e `categoria` (o `nome_categoria` do banco; acento, maiúsculas e espaços são ignorados).
2. O modelo devolve a tupla `(classe_detectada: str, probabilidade: float de 0 a 1)`.
3. A resposta traz `pertinente`, `categoria_informada`, `categoria_detectada`, `confianca` (0–100, 2 casas) e `mensagem` em PT-BR.
4. Regra: pertinente ⇔ `classe_detectada == categoria_informada` **e** `probabilidade >= LIMIAR_CONFIANCA`. Se o modelo viu outra categoria ou `nao_residuo`, a mensagem diz o que ele viu.
5. Arquivo que não é imagem, grande demais ou com resolução absurda é um veredito sobre a foto: HTTP 200 com `pertinente: false`, `categoria_detectada: null` e `confianca: null`.
6. Erro de integração (categoria fora do catálogo, URL fora do storage) → 422; storage não entregou a foto → 502; `X-Api-Key` errada → 401.

**Decisões inferidas** (se alguma não bater com o que vocês querem, ajuste aqui antes de executar):

- **CLIP zero-shot** em vez de treinar uma CNN: não precisa de dataset rotulado, cabe na restrição de custo zero (open-source, CPU) e cada categoria vira um punhado de frases em `categorias.py`. Se a equipe já tiver um modelo treinado, ele entra implementando `classificar(imagem) -> tuple[str, float]`.
- **Sem estado:** o validador não conecta no PostgreSQL nem no Redis; quem grava o resultado é o serviço que cria a postagem.
- **Triagem auxiliar:** é a "checagem automática fraca" do schema — não altera `status_validacao_id`, `saldo_confianca` nem pontos; a votação comunitária continua decidindo.
- **Python + FastAPI**, a mesma stack do ia-eko.

## Global Constraints

- Custo zero: modelo open-source rodando local, só CPU; nenhuma API paga de visão.
- Python ≥ 3.11 no desenvolvimento; imagem Docker `python:3.12-slim`; torch só CPU.
- Contrato do modelo: `classificar(imagem: PIL.Image.Image) -> tuple[str, float]` = `(classe_detectada, probabilidade 0–1)`.
- `confianca` na resposta: 0–100 com 2 casas (cabe em `DECIMAL(5,2)` de `triagem_automatica_confianca`).
- Categorias aceitas: `papel`, `plastico`, `vidro`, `metal`, `organico`; classe interna do modelo: `nao_residuo`.
- `LIMIAR_CONFIANCA` padrão `0.5`; foto até `10_000_000` bytes e `50_000_000` pixels.
- Download só de hosts listados em `HOSTS_PERMITIDOS` (igualdade exata), sem seguir redirect.
- Autenticação: header `X-Api-Key` igual a `CHAVE_API` (mínimo 16 caracteres).
- Deploy: 1 pod, 1 worker, CPU, no k3s da EC2 t3.large (2 vCPU / 8 GiB) que já roda autenticacao, cadastro, calendario e ia-eko — memória enxuta e medida.
- Nomes no código em português sem acento (padrão do schema); mensagens para o morador em PT-BR com acento.
- Comandos em bash (no Windows: Git Bash). `pytest` sempre da raiz do repositório, com o venv ativo.

## Review Focus

1. **Retrato salvo "deitado" com tag EXIF** (é assim que o celular grava foto em pé) → o modelo tem que receber a foto em pé. Teste: Task 4, `test_retrato_salvo_deitado_com_exif_fica_em_pe`.
2. **URL assinada do storage** (`?alt=media&token=…`, `%2F` no caminho) → tem que chegar idêntica ao storage, senão vira 403/404. Teste: Task 3, `test_url_assinada_chega_intacta_ao_storage`.
3. **Foto HEIC de iPhone** → aceita como qualquer JPEG. Teste: Task 4, `test_aceita_heic_do_iphone`.
4. **Arquivo pequeno com resolução absurda** (bomba de descompressão) → recusado antes de decodificar, sem derrubar o pod. Teste: Task 4, `test_resolucao_acima_do_limite_e_recusada_antes_de_decodificar`.
5. **Storage responde 200 com página de erro HTML** (link expirado, tela de login) → veredito `pertinente: false` com mensagem, nunca 500. Teste: Task 6, `test_arquivo_que_nao_e_imagem_vira_veredito_nao_pertinente`.

---

## Estrutura de arquivos

Repositório novo `ecociente-validador-fotos/`:

```
validador_fotos/
  categorias.py     catálogo: slug, nome em PT-BR e frases do CLIP; normaliza o nome_categoria
  pertinencia.py    regra de negócio: tupla do modelo -> ResultadoValidacao
  foto.py           download anti-SSRF + abertura da imagem (RGB, EXIF, HEIC, limites)
  classificador.py  Protocol Classificador + agregação por classe + ClassificadorClip
  configuracoes.py  variáveis de ambiente
  app.py            FastAPI: rota, autenticação, erros -> status HTTP
  calibracao.py     CLI: mede o acerto em fotos reais e ajuda a escolher o limiar
tests/              um test_<módulo>.py por módulo + conftest.py
requirements.txt  requirements-dev.txt  pyproject.toml
.env.example  .gitignore  .dockerignore  Dockerfile  README.md
```

## Fora do escopo (planos separados)

- Chamar o validador no serviço que cria a postagem e gravar `triagem_automatica_*` — o contrato fica no README (Task 7).
- Deployment/Service/Secret no `devops-infra-ecociente` — recursos e probes ficam no README (Task 7).
- Tela do app exibindo a `mensagem`.

---

### Task 1: Esqueleto do projeto + catálogo de categorias

**Files:**
- Create: `requirements.txt`, `requirements-dev.txt`, `pyproject.toml`, `.gitignore`, `validador_fotos/__init__.py` (vazio)
- Create: `validador_fotos/categorias.py`
- Test: `tests/test_categorias.py`

**Interfaces:**
- Consumes: nada.
- Produces:
  - `Classe` — dataclass congelada `(slug: str, nome: str, prompts: tuple[str, ...])`
  - `CATEGORIAS: dict[str, Classe]` — chaves `papel`, `plastico`, `vidro`, `metal`, `organico`
  - `NAO_RESIDUO: Classe` (slug `"nao_residuo"`) e `TODAS_AS_CLASSES: tuple[Classe, ...]` (as 5 categorias + `NAO_RESIDUO`)
  - `normalizar_categoria(texto: str) -> str`

- [ ] **Step 1: Criar o repositório e o ambiente virtual**

```bash
mkdir ecociente-validador-fotos && cd ecociente-validador-fotos
git init
python -m venv .venv
source .venv/Scripts/activate   # Git Bash no Windows; Linux/macOS: source .venv/bin/activate
```

- [ ] **Step 2: Criar os arquivos de dependências e configuração**

`requirements.txt`:

```
fastapi>=0.115
uvicorn[standard]>=0.30
httpx>=0.27
pillow>=10.4
pillow-heif>=1.0
pydantic-settings>=2.4
transformers>=4.44
torch>=2.3
```

`requirements-dev.txt`:

```
-r requirements.txt
pytest>=8.3
```

`pyproject.toml`:

```toml
[tool.pytest.ini_options]
pythonpath = ["."]
testpaths = ["tests"]
```

`.gitignore`:

```
.venv/
__pycache__/
.pytest_cache/
.env
amostras/
```

E crie `validador_fotos/__init__.py` vazio.

- [ ] **Step 3: Instalar as dependências**

Só em Linux **sem GPU**: instale antes o torch de CPU (a roda padrão do PyPI no Linux traz CUDA e pesa vários GB). No Windows, pule esta linha.

```bash
pip install torch --index-url https://download.pytorch.org/whl/cpu
```

```bash
pip install -r requirements-dev.txt
```

Expected: termina com `Successfully installed ...`, sem erros.

- [ ] **Step 4: Escrever o teste que falha**

`tests/test_categorias.py`:

```python
from validador_fotos.categorias import CATEGORIAS, NAO_RESIDUO, TODAS_AS_CLASSES, normalizar_categoria


def test_normaliza_nome_vindo_do_banco():
    assert normalizar_categoria("Plástico") == "plastico"
    assert normalizar_categoria("  ORGÂNICO ") == "organico"
    assert normalizar_categoria("Papel   Papelão") == "papel_papelao"


def test_catalogo_tem_as_categorias_do_app():
    assert set(CATEGORIAS) == {"papel", "plastico", "vidro", "metal", "organico"}


def test_chave_do_catalogo_e_o_proprio_slug_normalizado():
    for slug, classe in CATEGORIAS.items():
        assert classe.slug == slug == normalizar_categoria(slug)


def test_nao_residuo_e_classe_do_modelo_mas_nao_categoria_aceita():
    assert NAO_RESIDUO.slug not in CATEGORIAS
    assert NAO_RESIDUO in TODAS_AS_CLASSES


def test_prompts_nao_se_repetem_entre_classes():
    prompts = [prompt for classe in TODAS_AS_CLASSES for prompt in classe.prompts]
    assert len(prompts) == len(set(prompts))
```

- [ ] **Step 5: Rodar e ver falhar**

Run: `pytest tests/test_categorias.py -v`
Expected: erro de coleta `ModuleNotFoundError: No module named 'validador_fotos.categorias'`

- [ ] **Step 6: Implementar o catálogo**

Antes de escrever, confira o seed de `tb_lkp_categorias_residuos`: cada slug abaixo tem que ser o `nome_categoria` normalizado (ex.: "Plástico" → `plastico`). Se o seed usar outro nome (ex.: "Papel e Papelão" → `papel_e_papelao`), troque o slug aqui e no `test_catalogo_tem_as_categorias_do_app`.

`validador_fotos/categorias.py`:

```python
"""Catálogo das categorias aceitas e das descrições (prompts) que o CLIP compara com a foto."""
import unicodedata
from dataclasses import dataclass


@dataclass(frozen=True)
class Classe:
    slug: str
    nome: str  # como aparece nas mensagens para o morador
    prompts: tuple[str, ...]  # em inglês: o CLIP foi treinado com legendas em inglês


CATEGORIAS: dict[str, Classe] = {
    classe.slug: classe
    for classe in (
        Classe("papel", "papel", ("a photo of cardboard boxes", "a photo of paper waste", "a photo of old newspapers and magazines")),
        Classe("plastico", "plástico", ("a photo of a plastic bottle", "a photo of plastic packaging", "a photo of plastic bags")),
        Classe("vidro", "vidro", ("a photo of a glass bottle", "a photo of a glass jar", "a photo of broken glass")),
        Classe("metal", "metal", ("a photo of an aluminum can", "a photo of a tin can", "a photo of scrap metal")),
        Classe("organico", "orgânico", ("a photo of food scraps", "a photo of fruit and vegetable peels", "a photo of coffee grounds and eggshells")),
    )
}

# Classe de "fundo": dá ao modelo uma saída para fotos que não mostram resíduo nenhum.
NAO_RESIDUO = Classe(
    "nao_residuo",
    "não resíduo",
    ("a photo of a person", "a selfie", "a photo of a pet", "a screenshot of a phone screen", "a blurry dark photo"),
)

TODAS_AS_CLASSES: tuple[Classe, ...] = (*CATEGORIAS.values(), NAO_RESIDUO)


def normalizar_categoria(texto: str) -> str:
    """'  Plástico ' -> 'plastico': aceita o nome_categoria do banco do jeito que está."""
    sem_acento = unicodedata.normalize("NFKD", texto).encode("ascii", "ignore").decode("ascii")
    return "_".join(sem_acento.lower().split())
```

- [ ] **Step 7: Rodar e ver passar**

Run: `pytest tests/test_categorias.py -v`
Expected: `5 passed`

- [ ] **Step 8: Commit**

```bash
git add requirements.txt requirements-dev.txt pyproject.toml .gitignore validador_fotos tests
git commit -m "feat: esqueleto do projeto e catálogo de categorias"
```

---

### Task 2: Regra de pertinência (tupla → veredito)

**Files:**
- Create: `validador_fotos/pertinencia.py`
- Test: `tests/test_pertinencia.py`

**Interfaces:**
- Consumes: `CATEGORIAS`, `NAO_RESIDUO` (Task 1).
- Produces:
  - `ResultadoValidacao` (pydantic `BaseModel`): `pertinente: bool`, `categoria_informada: str`, `categoria_detectada: str | None`, `confianca: float | None` (0–100), `mensagem: str`. É também o corpo da resposta HTTP (Task 6).
  - `decidir_pertinencia(categoria_informada: str, predicao: tuple[str, float], limiar: float) -> ResultadoValidacao` — `categoria_informada` já normalizada e presente em `CATEGORIAS`; `predicao[0]` é um slug de `TODAS_AS_CLASSES`; `limiar` entre 0 e 1.
  - `rejeitar_foto(categoria_informada: str, motivo: str) -> ResultadoValidacao`

- [ ] **Step 1: Escrever o teste que falha**

`tests/test_pertinencia.py`:

```python
from validador_fotos.pertinencia import decidir_pertinencia, rejeitar_foto

LIMIAR = 0.5


def test_mesma_categoria_acima_do_limiar_e_pertinente():
    resultado = decidir_pertinencia("plastico", ("plastico", 0.8734), LIMIAR)
    assert resultado.pertinente is True
    assert resultado.categoria_detectada == "plastico"
    assert resultado.confianca == 87.34
    assert resultado.mensagem == "Foto compatível com plástico."


def test_confianca_exatamente_no_limiar_e_pertinente():
    assert decidir_pertinencia("vidro", ("vidro", 0.5), LIMIAR).pertinente is True


def test_mesma_categoria_abaixo_do_limiar_nao_e_pertinente():
    resultado = decidir_pertinencia("vidro", ("vidro", 0.31), LIMIAR)
    assert resultado.pertinente is False
    assert resultado.mensagem.startswith("Não deu para confirmar que a foto é de vidro")


def test_outra_categoria_nao_e_pertinente_e_diz_o_que_viu():
    resultado = decidir_pertinencia("plastico", ("vidro", 0.9), LIMIAR)
    assert resultado.pertinente is False
    assert resultado.categoria_detectada == "vidro"
    assert resultado.mensagem == "A foto parece conter vidro, não plástico."


def test_foto_sem_residuo_nao_e_pertinente():
    resultado = decidir_pertinencia("metal", ("nao_residuo", 0.95), LIMIAR)
    assert resultado.pertinente is False
    assert resultado.mensagem == "A foto não parece mostrar um resíduo."


def test_confianca_vira_0_a_100_com_duas_casas():
    assert decidir_pertinencia("papel", ("papel", 0.123456), LIMIAR).confianca == 12.35


def test_rejeitar_foto_nao_tem_classe_nem_confianca():
    resultado = rejeitar_foto("organico", "O arquivo não é uma imagem válida.")
    assert resultado.model_dump() == {
        "pertinente": False,
        "categoria_informada": "organico",
        "categoria_detectada": None,
        "confianca": None,
        "mensagem": "O arquivo não é uma imagem válida.",
    }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `pytest tests/test_pertinencia.py -v`
Expected: erro de coleta `ModuleNotFoundError: No module named 'validador_fotos.pertinencia'`

- [ ] **Step 3: Implementar a regra**

`validador_fotos/pertinencia.py`:

```python
"""Regra de negócio: transforma a tupla do modelo no veredito que a API devolve."""
from pydantic import BaseModel, Field

from validador_fotos.categorias import CATEGORIAS, NAO_RESIDUO


class ResultadoValidacao(BaseModel):
    pertinente: bool
    categoria_informada: str
    categoria_detectada: str | None = Field(description="None quando o arquivo nem chegou ao modelo")
    confianca: float | None = Field(
        ge=0, le=100, description="0–100 com 2 casas: vai direto para tb_postagens.triagem_automatica_confianca"
    )
    mensagem: str


def decidir_pertinencia(categoria_informada: str, predicao: tuple[str, float], limiar: float) -> ResultadoValidacao:
    """predicao = (classe_detectada, probabilidade 0–1), exatamente o que o Classificador devolve."""
    detectada, probabilidade = predicao
    informada = CATEGORIAS[categoria_informada].nome
    if detectada == NAO_RESIDUO.slug:
        pertinente, mensagem = False, "A foto não parece mostrar um resíduo."
    elif detectada != categoria_informada:
        pertinente, mensagem = False, f"A foto parece conter {CATEGORIAS[detectada].nome}, não {informada}."
    elif probabilidade < limiar:
        pertinente = False
        mensagem = f"Não deu para confirmar que a foto é de {informada}. Tente de mais perto e com boa luz."
    else:
        pertinente, mensagem = True, f"Foto compatível com {informada}."
    return ResultadoValidacao(
        pertinente=pertinente,
        categoria_informada=categoria_informada,
        categoria_detectada=detectada,
        confianca=round(probabilidade * 100, 2),
        mensagem=mensagem,
    )


def rejeitar_foto(categoria_informada: str, motivo: str) -> ResultadoValidacao:
    """Veredito para arquivo que nem chega ao modelo (não é imagem, grande demais...)."""
    return ResultadoValidacao(
        pertinente=False,
        categoria_informada=categoria_informada,
        categoria_detectada=None,
        confianca=None,
        mensagem=motivo,
    )
```

- [ ] **Step 4: Rodar e ver passar**

Run: `pytest tests/test_pertinencia.py -v`
Expected: `7 passed`

- [ ] **Step 5: Commit**

```bash
git add validador_fotos/pertinencia.py tests/test_pertinencia.py
git commit -m "feat: regra de pertinência da foto"
```

---

### Task 3: Download seguro da foto

**Files:**
- Create: `validador_fotos/foto.py`
- Test: `tests/test_foto.py`

**Interfaces:**
- Consumes: nada.
- Produces:
  - Exceções `PedidoInvalido` (vira HTTP 422), `FotoInvalida` (vira veredito "não pertinente"), `FotoIndisponivel` (vira HTTP 502).
  - `baixar_foto(url: str, *, hosts_permitidos: frozenset[str], tamanho_maximo: int, cliente: httpx.Client) -> bytes` — `hosts_permitidos` em minúsculas. Levanta `PedidoInvalido` (URL malformada, esquema que não é http(s), host fora da lista), `FotoInvalida` (passou de `tamanho_maximo`) ou `FotoIndisponivel` (status ≠ 200, redirect, erro de rede/timeout).

- [ ] **Step 1: Escrever o teste que falha**

`tests/test_foto.py`:

```python
import httpx
import pytest

from validador_fotos.foto import FotoIndisponivel, FotoInvalida, PedidoInvalido, baixar_foto

HOSTS = frozenset({"storage.exemplo.com"})
URL = "https://storage.exemplo.com/postagens/42.jpg"


def storage_falso(resposta: httpx.Response | Exception, **opcoes) -> httpx.Client:
    """Cliente httpx que, em vez de ir à rede, devolve `resposta` (ou levanta a exceção)."""

    def responder(request: httpx.Request) -> httpx.Response:
        if isinstance(resposta, Exception):
            raise resposta
        return resposta

    return httpx.Client(transport=httpx.MockTransport(responder), **opcoes)


def baixar(url: str = URL, cliente: httpx.Client | None = None, tamanho_maximo: int = 1024) -> bytes:
    cliente = cliente or storage_falso(httpx.Response(200, content=b"bytes-da-foto"))
    return baixar_foto(url, hosts_permitidos=HOSTS, tamanho_maximo=tamanho_maximo, cliente=cliente)


# ---------- baixar_foto ----------

def test_baixa_foto_de_host_permitido():
    assert baixar() == b"bytes-da-foto"


def test_url_assinada_chega_intacta_ao_storage():
    recebidas = []

    def responder(request: httpx.Request) -> httpx.Response:
        recebidas.append(str(request.url))
        return httpx.Response(200, content=b"ok")

    url = "https://storage.exemplo.com/v0/b/app/o/postagens%2F42.jpg?alt=media&token=abc-123"
    baixar(url, cliente=httpx.Client(transport=httpx.MockTransport(responder)))
    assert recebidas == [url]


@pytest.mark.parametrize("url", ["ftp://storage.exemplo.com/42.jpg", "file:///etc/passwd", "nao-e-url"])
def test_recusa_esquema_que_nao_e_http(url):
    with pytest.raises(PedidoInvalido):
        baixar(url)


@pytest.mark.parametrize(
    "url",
    [
        "https://169.254.169.254/latest/meta-data/",  # metadados da AWS: SSRF clássico
        "http://localhost:8000/saude",
        "https://storage.exemplo.com@evil.com/42.jpg",  # truque de userinfo
    ],
)
def test_recusa_host_fora_da_lista(url):
    with pytest.raises(PedidoInvalido, match="Host não permitido"):
        baixar(url)


def test_foto_maior_que_o_limite_e_invalida():
    cliente = storage_falso(httpx.Response(200, content=b"x" * 2048))
    with pytest.raises(FotoInvalida, match="limite"):
        baixar(cliente=cliente, tamanho_maximo=1024)


def test_status_diferente_de_200_vira_foto_indisponivel():
    with pytest.raises(FotoIndisponivel, match="404"):
        baixar(cliente=storage_falso(httpx.Response(404)))


def test_nao_segue_redirect_mesmo_com_cliente_que_seguiria():
    redirect = httpx.Response(302, headers={"Location": "http://10.0.0.1/interno"})
    with pytest.raises(FotoIndisponivel, match="302"):
        baixar(cliente=storage_falso(redirect, follow_redirects=True))


def test_timeout_do_storage_vira_foto_indisponivel():
    with pytest.raises(FotoIndisponivel):
        baixar(cliente=storage_falso(httpx.ReadTimeout("lento demais")))
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `pytest tests/test_foto.py -v`
Expected: erro de coleta `ModuleNotFoundError: No module named 'validador_fotos.foto'`

- [ ] **Step 3: Implementar o download**

Pontos de segurança: a URL é validada e baixada com o **mesmo** parser (`httpx.URL`), então não há divergência entre o host checado e o host acessado; o redirect é desligado no próprio pedido (vale mesmo se alguém passar um cliente que segue redirects); o limite de tamanho é aplicado enquanto os bytes chegam.

`validador_fotos/foto.py`:

```python
"""Busca a foto no storage com segurança e a entrega pronta (RGB, em pé) para o modelo."""
import httpx


class PedidoInvalido(Exception):
    """URL que o serviço se recusa a buscar -> HTTP 422 (erro de integração de quem chamou)."""


class FotoInvalida(Exception):
    """O arquivo baixado não serve como foto -> veredito "não pertinente" com este motivo."""


class FotoIndisponivel(Exception):
    """O storage não entregou a foto (rede, timeout, 404...) -> HTTP 502; vale tentar de novo."""


def baixar_foto(url: str, *, hosts_permitidos: frozenset[str], tamanho_maximo: int, cliente: httpx.Client) -> bytes:
    try:
        destino = httpx.URL(url)
    except httpx.InvalidURL as erro:
        raise PedidoInvalido("URL da foto malformada.") from erro
    if destino.scheme not in ("http", "https"):
        raise PedidoInvalido("A URL da foto precisa ser http(s).")
    if destino.host not in hosts_permitidos:  # anti-SSRF: só busca no storage do app
        raise PedidoInvalido(f"Host não permitido: {destino.host or '(vazio)'}.")
    try:
        with cliente.stream("GET", destino, follow_redirects=False) as resposta:
            if resposta.status_code != 200:
                raise FotoIndisponivel(f"O storage respondeu HTTP {resposta.status_code}.")
            dados = bytearray()
            for bloco in resposta.iter_bytes():
                dados += bloco
                if len(dados) > tamanho_maximo:
                    raise FotoInvalida(f"A foto passa do limite de {tamanho_maximo / 1_000_000:g} MB.")
    except httpx.HTTPError as erro:
        raise FotoIndisponivel("Não foi possível baixar a foto do storage.") from erro
    return bytes(dados)
```

- [ ] **Step 4: Rodar e ver passar**

Run: `pytest tests/test_foto.py -v`
Expected: `12 passed`

- [ ] **Step 5: Commit**

```bash
git add validador_fotos/foto.py tests/test_foto.py
git commit -m "feat: download da foto com allowlist de hosts e limite de tamanho"
```

---

### Task 4: Abertura da imagem (RGB, EXIF, HEIC, limites)

**Files:**
- Modify: `validador_fotos/foto.py` (arquivo inteiro abaixo)
- Modify: `tests/test_foto.py` (troca os imports do topo e acrescenta testes no fim)
- Create: `tests/conftest.py`

**Interfaces:**
- Consumes: `FotoInvalida` (Task 3).
- Produces:
  - `abrir_imagem(dados: bytes) -> PIL.Image.Image` — sempre em modo `RGB` e em pé; levanta `FotoInvalida` ("O arquivo não é uma imagem válida." ou "A foto tem resolução grande demais.").
  - Constante `LIMITE_PIXELS = 50_000_000`.
  - Fixture `png_valido` (PNG 8×8 em bytes) em `tests/conftest.py` — usada também na Task 6.

- [ ] **Step 1: Criar a fixture**

`tests/conftest.py`:

```python
import io

import pytest
from PIL import Image


@pytest.fixture
def png_valido() -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", (8, 8), (200, 30, 30)).save(buffer, format="PNG")
    return buffer.getvalue()
```

- [ ] **Step 2: Escrever os testes que falham**

No topo de `tests/test_foto.py`, troque o bloco de imports por:

```python
import io

import httpx
import pytest
from PIL import Image

from validador_fotos import foto
from validador_fotos.foto import FotoIndisponivel, FotoInvalida, PedidoInvalido, abrir_imagem, baixar_foto
```

E acrescente no fim do arquivo:

```python
# ---------- abrir_imagem ----------

def test_abre_png_valido_como_rgb(png_valido):
    imagem = abrir_imagem(png_valido)
    assert imagem.mode == "RGB"
    assert imagem.size == (8, 8)


def test_png_com_transparencia_vira_rgb():
    buffer = io.BytesIO()
    Image.new("RGBA", (4, 4), (0, 0, 255, 128)).save(buffer, format="PNG")
    assert abrir_imagem(buffer.getvalue()).mode == "RGB"


def test_retrato_salvo_deitado_com_exif_fica_em_pe():
    exif = Image.Exif()
    exif[0x0112] = 6  # Orientation = girar 90°: o que o celular grava numa foto em pé
    buffer = io.BytesIO()
    Image.new("RGB", (40, 20), "green").save(buffer, format="JPEG", exif=exif)
    assert abrir_imagem(buffer.getvalue()).size == (20, 40)


def test_aceita_heic_do_iphone():
    buffer = io.BytesIO()
    Image.new("RGB", (40, 20), "blue").save(buffer, format="HEIF")
    imagem = abrir_imagem(buffer.getvalue())
    assert (imagem.mode, imagem.size) == ("RGB", (40, 20))


def test_resolucao_acima_do_limite_e_recusada_antes_de_decodificar(png_valido, monkeypatch):
    monkeypatch.setattr(foto, "LIMITE_PIXELS", 10)  # o PNG de teste tem 8x8 = 64 px
    with pytest.raises(FotoInvalida, match="resolução"):
        abrir_imagem(png_valido)


def test_bytes_que_nao_sao_imagem_viram_foto_invalida():
    with pytest.raises(FotoInvalida, match="imagem válida"):
        abrir_imagem(b"<html>Access Denied</html>")


def test_imagem_truncada_vira_foto_invalida(png_valido):
    with pytest.raises(FotoInvalida):
        abrir_imagem(png_valido[: len(png_valido) // 2])
```

> `test_aceita_heic_do_iphone` gera um HEIC usando o próprio pillow-heif. Ele só passa a funcionar quando o `foto.py` registrar o opener de HEIF no Step 4.

- [ ] **Step 3: Rodar e ver falhar**

Run: `pytest tests/test_foto.py -v`
Expected: erro de coleta `ImportError: cannot import name 'abrir_imagem' from 'validador_fotos.foto'`

- [ ] **Step 4: Implementar**

Substitua `validador_fotos/foto.py` inteiro por:

```python
"""Busca a foto no storage com segurança e a entrega pronta (RGB, em pé) para o modelo."""
import io

import httpx
from PIL import Image, ImageOps
from pillow_heif import register_heif_opener

register_heif_opener()  # aceita HEIC/HEIF, o formato padrão das fotos de iPhone

LIMITE_PIXELS = 50_000_000  # ~50 MP: cobre câmera de celular e barra "bomba de descompressão"


class PedidoInvalido(Exception):
    """URL que o serviço se recusa a buscar -> HTTP 422 (erro de integração de quem chamou)."""


class FotoInvalida(Exception):
    """O arquivo baixado não serve como foto -> veredito "não pertinente" com este motivo."""


class FotoIndisponivel(Exception):
    """O storage não entregou a foto (rede, timeout, 404...) -> HTTP 502; vale tentar de novo."""


def baixar_foto(url: str, *, hosts_permitidos: frozenset[str], tamanho_maximo: int, cliente: httpx.Client) -> bytes:
    try:
        destino = httpx.URL(url)
    except httpx.InvalidURL as erro:
        raise PedidoInvalido("URL da foto malformada.") from erro
    if destino.scheme not in ("http", "https"):
        raise PedidoInvalido("A URL da foto precisa ser http(s).")
    if destino.host not in hosts_permitidos:  # anti-SSRF: só busca no storage do app
        raise PedidoInvalido(f"Host não permitido: {destino.host or '(vazio)'}.")
    try:
        with cliente.stream("GET", destino, follow_redirects=False) as resposta:
            if resposta.status_code != 200:
                raise FotoIndisponivel(f"O storage respondeu HTTP {resposta.status_code}.")
            dados = bytearray()
            for bloco in resposta.iter_bytes():
                dados += bloco
                if len(dados) > tamanho_maximo:
                    raise FotoInvalida(f"A foto passa do limite de {tamanho_maximo / 1_000_000:g} MB.")
    except httpx.HTTPError as erro:
        raise FotoIndisponivel("Não foi possível baixar a foto do storage.") from erro
    return bytes(dados)


def abrir_imagem(dados: bytes) -> Image.Image:
    try:
        imagem = Image.open(io.BytesIO(dados))  # só lê o cabeçalho; ainda não decodificou
        if imagem.width * imagem.height > LIMITE_PIXELS:
            raise FotoInvalida("A foto tem resolução grande demais.")
        imagem.draft("RGB", (1024, 1024))  # JPEG: já decodifica reduzida (menos RAM e CPU)
        imagem = ImageOps.exif_transpose(imagem)  # celular salva retrato "deitado" + tag EXIF
        return imagem.convert("RGB")
    except FotoInvalida:
        raise
    except Exception as erro:  # o Pillow lança tipos variados para arquivo corrompido ou desconhecido
        raise FotoInvalida("O arquivo não é uma imagem válida.") from erro
```

Notas: o `except Exception` é proposital — é a fronteira com bytes não confiáveis e o Pillow lança `OSError`, `ValueError`, `SyntaxError` etc. conforme o defeito; o `except FotoInvalida: raise` antes dele preserva a mensagem de resolução. O `draft` só tem efeito em JPEG (decodifica direto em 1/2, 1/4 ou 1/8); em PNG/HEIC não faz nada.

- [ ] **Step 5: Rodar e ver passar**

Run: `pytest tests/test_foto.py -v`
Expected: `19 passed`

Run: `pytest`
Expected: `31 passed`

- [ ] **Step 6: Commit**

```bash
git add validador_fotos/foto.py tests/test_foto.py tests/conftest.py
git commit -m "feat: abertura da imagem com EXIF, HEIC e limite de resolução"
```

---

### Task 5: Classificador (CLIP zero-shot devolvendo a tupla)

**Files:**
- Create: `validador_fotos/classificador.py`
- Test: `tests/test_classificador.py`
- Modify: `pyproject.toml` (arquivo inteiro abaixo)

**Interfaces:**
- Consumes: `TODAS_AS_CLASSES` (Task 1).
- Produces:
  - `MODELO_PADRAO = "openai/clip-vit-base-patch32"`
  - `Classificador` — `Protocol` com `classificar(imagem: PIL.Image.Image) -> tuple[str, float]`
  - `agregar_por_classe(saida: list[dict], classe_do_prompt: dict[str, str]) -> tuple[str, float]` — `saida` no formato do pipeline do transformers: `[{"label": str, "score": float}, ...]`
  - `ClassificadorClip(nome_modelo: str = MODELO_PADRAO)` — implementa `Classificador`; carrega o modelo no construtor (lento, uma vez só).

Por que somar: cada categoria tem 3 frases e `nao_residuo` tem 5; o pipeline faz softmax entre **todas** as frases, então a probabilidade de uma classe é a soma das frases dela. Por isso o modelo tem que ser um CLIP — SigLIP usa sigmoid e a soma passa de 1.

- [ ] **Step 1: Separar os testes lentos do modelo real**

Substitua `pyproject.toml` por:

```toml
[tool.pytest.ini_options]
pythonpath = ["."]
testpaths = ["tests"]
markers = ["modelo: baixa e roda o CLIP de verdade (lento; ~600 MB no primeiro uso)"]
addopts = "-m 'not modelo'"
```

Assim `pytest` roda só os testes rápidos e `pytest -m modelo` roda os que carregam o CLIP.

- [ ] **Step 2: Escrever o teste que falha**

`tests/test_classificador.py`:

```python
import pytest
from PIL import Image

from validador_fotos.categorias import TODAS_AS_CLASSES
from validador_fotos.classificador import MODELO_PADRAO, ClassificadorClip, agregar_por_classe

CLASSE_DO_PROMPT = {
    "a photo of a plastic bottle": "plastico",
    "a photo of plastic bags": "plastico",
    "a photo of a glass jar": "vidro",
    "a selfie": "nao_residuo",
}


def test_soma_os_prompts_da_mesma_classe():
    saida = [  # formato do pipeline zero-shot do transformers, já ordenado por score
        {"label": "a photo of a glass jar", "score": 0.40},
        {"label": "a photo of a plastic bottle", "score": 0.35},
        {"label": "a photo of plastic bags", "score": 0.20},
        {"label": "a selfie", "score": 0.05},
    ]
    classe, probabilidade = agregar_por_classe(saida, CLASSE_DO_PROMPT)
    assert classe == "plastico"  # nenhum prompt de plástico venceu sozinho, mas a soma vence
    assert probabilidade == pytest.approx(0.55)


def test_devolve_a_tupla_classe_probabilidade():
    assert agregar_por_classe([{"label": "a selfie", "score": 1.0}], CLASSE_DO_PROMPT) == ("nao_residuo", 1.0)


@pytest.mark.modelo
def test_clip_de_verdade_devolve_classe_do_catalogo():
    classificador = ClassificadorClip(MODELO_PADRAO)
    classe, probabilidade = classificador.classificar(Image.new("RGB", (224, 224), "white"))
    assert classe in {c.slug for c in TODAS_AS_CLASSES}
    assert 0.0 <= probabilidade <= 1.0
```

- [ ] **Step 3: Rodar e ver falhar**

Run: `pytest tests/test_classificador.py -v`
Expected: erro de coleta `ModuleNotFoundError: No module named 'validador_fotos.classificador'`

- [ ] **Step 4: Implementar**

`validador_fotos/classificador.py`:

```python
"""Modelo de visão. Contrato: classificar(imagem) -> (classe_detectada, probabilidade 0–1)."""
import threading
from typing import Protocol

from PIL import Image

from validador_fotos.categorias import TODAS_AS_CLASSES

MODELO_PADRAO = "openai/clip-vit-base-patch32"


class Classificador(Protocol):
    """Qualquer modelo serve (CLIP, uma CNN treinada pela equipe...) desde que devolva a tupla."""

    def classificar(self, imagem: Image.Image) -> tuple[str, float]: ...


def agregar_por_classe(saida: list[dict], classe_do_prompt: dict[str, str]) -> tuple[str, float]:
    """Soma as probabilidades dos prompts de cada classe e devolve a classe vencedora."""
    totais: dict[str, float] = {}
    for item in saida:
        classe = classe_do_prompt[item["label"]]
        totais[classe] = totais.get(classe, 0.0) + item["score"]
    vencedora = max(totais, key=totais.__getitem__)
    return vencedora, totais[vencedora]


class ClassificadorClip:
    """Zero-shot com CLIP: compara a foto com as descrições de categorias.py, sem dataset nem treino."""

    def __init__(self, nome_modelo: str = MODELO_PADRAO) -> None:
        from transformers import pipeline  # import pesado (torch): só quando o modelo real é usado

        self._pipeline = pipeline("zero-shot-image-classification", model=nome_modelo)
        self._classe_do_prompt = {p: classe.slug for classe in TODAS_AS_CLASSES for p in classe.prompts}
        self._trava = threading.Lock()  # o FastAPI roda rotas síncronas em várias threads ao mesmo tempo

    def classificar(self, imagem: Image.Image) -> tuple[str, float]:
        with self._trava:
            saida = self._pipeline(imagem, candidate_labels=list(self._classe_do_prompt), hypothesis_template="{}")
        return agregar_por_classe(saida, self._classe_do_prompt)
```

`hypothesis_template="{}"` faz o pipeline usar as frases do catálogo como estão (o padrão do transformers acrescentaria "This is a photo of …" na frente).

- [ ] **Step 5: Rodar os testes rápidos**

Run: `pytest tests/test_classificador.py -v`
Expected: `2 passed, 1 deselected`

- [ ] **Step 6: Rodar o teste com o CLIP de verdade**

Run: `pytest -m modelo -v`
Expected: `1 passed` (na primeira vez baixa ~600 MB para o cache do Hugging Face; leva alguns minutos)

- [ ] **Step 7: Commit**

```bash
git add pyproject.toml validador_fotos/classificador.py tests/test_classificador.py
git commit -m "feat: classificador CLIP zero-shot que devolve a tupla (classe, probabilidade)"
```

---

### Task 6: API HTTP (configuração, rota, autenticação, erros)

**Files:**
- Create: `validador_fotos/configuracoes.py`, `validador_fotos/app.py`, `.env.example`
- Test: `tests/test_configuracoes.py`, `tests/test_app.py`

**Interfaces:**
- Consumes: `CATEGORIAS`, `normalizar_categoria` (Task 1); `ResultadoValidacao`, `decidir_pertinencia`, `rejeitar_foto` (Task 2); `baixar_foto`, `PedidoInvalido`, `FotoInvalida`, `FotoIndisponivel` (Task 3); `abrir_imagem` e a fixture `png_valido` (Task 4); `Classificador`, `ClassificadorClip`, `MODELO_PADRAO` (Task 5).
- Produces:
  - `Configuracoes` (pydantic-settings, lê env/`.env`): `chave_api: str`, `hosts_permitidos: str`, `limiar_confianca: float`, `tamanho_maximo_bytes: int`, `timeout_download_segundos: float`, `modelo_clip: str`; propriedade `conjunto_hosts_permitidos -> frozenset[str]`.
  - `criar_app(configuracoes: Configuracoes | None = None, classificador: Classificador | None = None, cliente_http: httpx.Client | None = None) -> FastAPI` — fábrica que o uvicorn chama com `--factory`; sem argumentos, cria tudo de verdade (e carrega o CLIP).
  - HTTP: `GET /saude` → `{"status": "ok"}`; `POST /v1/validacoes` com header `X-Api-Key` e corpo `{"url_foto": str, "categoria": str}` → 200 `ResultadoValidacao` | 401 | 422 | 502.

- [ ] **Step 1: Escrever os testes que falham**

`tests/test_configuracoes.py`:

```python
import pytest
from pydantic import ValidationError

from validador_fotos.configuracoes import Configuracoes


def test_le_variaveis_de_ambiente(monkeypatch):
    monkeypatch.setenv("CHAVE_API", "c" * 32)
    monkeypatch.setenv("HOSTS_PERMITIDOS", " Storage.Exemplo.com , cdn.exemplo.com ,")
    monkeypatch.setenv("LIMIAR_CONFIANCA", "0.65")
    config = Configuracoes(_env_file=None)
    assert config.conjunto_hosts_permitidos == frozenset({"storage.exemplo.com", "cdn.exemplo.com"})
    assert config.limiar_confianca == 0.65
    assert config.tamanho_maximo_bytes == 10_000_000
    assert config.modelo_clip == "openai/clip-vit-base-patch32"


def test_chave_api_curta_e_recusada():
    with pytest.raises(ValidationError):
        Configuracoes(_env_file=None, chave_api="curta", hosts_permitidos="storage.exemplo.com")
```

`tests/test_app.py`:

```python
import httpx
from fastapi.testclient import TestClient
from PIL import Image

from validador_fotos.app import criar_app
from validador_fotos.configuracoes import Configuracoes

CHAVE = "chave-de-teste-com-mais-de-16"
URL_FOTO = "https://storage.exemplo.com/postagens/42.png"


class ClassificadorFalso:
    """Dublê do modelo: devolve a tupla configurada e guarda as imagens que recebeu."""

    def __init__(self, predicao: tuple[str, float]) -> None:
        self.predicao = predicao
        self.imagens: list[Image.Image] = []

    def classificar(self, imagem: Image.Image) -> tuple[str, float]:
        self.imagens.append(imagem)
        return self.predicao


def montar(conteudo: bytes, predicao=("plastico", 0.9123), status_storage=200):
    configuracoes = Configuracoes(_env_file=None, chave_api=CHAVE, hosts_permitidos="storage.exemplo.com")
    storage = httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(status_storage, content=conteudo)))
    classificador = ClassificadorFalso(predicao)
    return TestClient(criar_app(configuracoes, classificador, storage)), classificador


def validar(cliente: TestClient, categoria="Plástico", url=URL_FOTO, chave=CHAVE):
    return cliente.post("/v1/validacoes", json={"url_foto": url, "categoria": categoria}, headers={"X-Api-Key": chave})


def test_foto_pertinente_devolve_veredito_completo(png_valido):
    cliente, classificador = montar(png_valido)
    resposta = validar(cliente)
    assert resposta.status_code == 200
    assert resposta.json() == {
        "pertinente": True,
        "categoria_informada": "plastico",
        "categoria_detectada": "plastico",
        "confianca": 91.23,
        "mensagem": "Foto compatível com plástico.",
    }
    assert classificador.imagens[0].mode == "RGB"


def test_foto_de_outra_categoria_nao_e_pertinente(png_valido):
    cliente, _ = montar(png_valido, predicao=("vidro", 0.8))
    corpo = validar(cliente).json()
    assert corpo["pertinente"] is False
    assert corpo["mensagem"] == "A foto parece conter vidro, não plástico."


def test_arquivo_que_nao_e_imagem_vira_veredito_nao_pertinente():
    cliente, classificador = montar(b"<html>Access Denied</html>")
    resposta = validar(cliente)
    assert resposta.status_code == 200
    assert resposta.json() == {
        "pertinente": False,
        "categoria_informada": "plastico",
        "categoria_detectada": None,
        "confianca": None,
        "mensagem": "O arquivo não é uma imagem válida.",
    }
    assert classificador.imagens == []


def test_chave_api_errada_ou_ausente_retorna_401(png_valido):
    cliente, _ = montar(png_valido)
    assert validar(cliente, chave="errada").status_code == 401
    assert cliente.post("/v1/validacoes", json={"url_foto": URL_FOTO, "categoria": "vidro"}).status_code == 401


def test_categoria_fora_do_catalogo_retorna_422(png_valido):
    cliente, _ = montar(png_valido)
    resposta = validar(cliente, categoria="Eletrônico")
    assert resposta.status_code == 422
    assert "categoria desconhecida" in resposta.text


def test_host_nao_permitido_retorna_422_sem_baixar(png_valido):
    cliente, classificador = montar(png_valido)
    resposta = validar(cliente, url="https://169.254.169.254/latest/meta-data/")
    assert resposta.status_code == 422
    assert classificador.imagens == []


def test_storage_fora_do_ar_retorna_502(png_valido):
    cliente, _ = montar(png_valido, status_storage=503)
    assert validar(cliente).status_code == 502


def test_saude():
    cliente, _ = montar(b"")
    assert cliente.get("/saude").json() == {"status": "ok"}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `pytest tests/test_configuracoes.py tests/test_app.py -v`
Expected: erros de coleta `ModuleNotFoundError: No module named 'validador_fotos.configuracoes'`

- [ ] **Step 3: Implementar a configuração**

`validador_fotos/configuracoes.py`:

```python
"""Configuração por variáveis de ambiente (ou arquivo .env na raiz do projeto)."""
from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

from validador_fotos.classificador import MODELO_PADRAO


class Configuracoes(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    chave_api: str = Field(min_length=16)  # valor do header X-Api-Key que os serviços chamadores enviam
    hosts_permitidos: str  # hosts do storage de fotos, separados por vírgula
    limiar_confianca: float = Field(default=0.5, ge=0, le=1)
    tamanho_maximo_bytes: int = Field(default=10_000_000, gt=0)
    timeout_download_segundos: float = Field(default=10.0, gt=0)
    modelo_clip: str = MODELO_PADRAO  # tem que ser CLIP (softmax); SigLIP usa sigmoid e quebra a soma por classe

    @property
    def conjunto_hosts_permitidos(self) -> frozenset[str]:
        return frozenset(host.strip().lower() for host in self.hosts_permitidos.split(",") if host.strip())
```

- [ ] **Step 4: Implementar a API**

`validador_fotos/app.py`:

```python
"""API HTTP: POST /v1/validacoes (URL da foto + categoria) -> veredito de pertinência."""
import secrets
from typing import Annotated

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, field_validator

from validador_fotos.categorias import CATEGORIAS, normalizar_categoria
from validador_fotos.classificador import Classificador, ClassificadorClip
from validador_fotos.configuracoes import Configuracoes
from validador_fotos.foto import FotoIndisponivel, FotoInvalida, PedidoInvalido, abrir_imagem, baixar_foto
from validador_fotos.pertinencia import ResultadoValidacao, decidir_pertinencia, rejeitar_foto


class PedidoValidacao(BaseModel):
    url_foto: str = Field(max_length=2048)
    categoria: str = Field(description="nome_categoria de tb_lkp_categorias_residuos; acento e maiúsculas são ignorados")

    @field_validator("categoria")
    @classmethod
    def categoria_do_catalogo(cls, valor: str) -> str:
        slug = normalizar_categoria(valor)
        if slug not in CATEGORIAS:
            raise ValueError(f"categoria desconhecida; aceitas: {', '.join(CATEGORIAS)}")
        return slug


def criar_app(
    configuracoes: Configuracoes | None = None,
    classificador: Classificador | None = None,
    cliente_http: httpx.Client | None = None,
) -> FastAPI:
    """Fábrica: em produção cria tudo (e carrega o CLIP uma vez só); nos testes recebe dublês."""
    configuracoes = configuracoes or Configuracoes()
    classificador = classificador or ClassificadorClip(configuracoes.modelo_clip)
    cliente_http = cliente_http or httpx.Client(timeout=configuracoes.timeout_download_segundos)

    app = FastAPI(title="Validador de Fotos EcoCiente", version="1.0.0")

    @app.exception_handler(PedidoInvalido)
    async def _pedido_invalido(_: Request, erro: PedidoInvalido) -> JSONResponse:
        return JSONResponse(status_code=422, content={"detail": str(erro)})

    @app.exception_handler(FotoIndisponivel)
    async def _foto_indisponivel(_: Request, erro: FotoIndisponivel) -> JSONResponse:
        return JSONResponse(status_code=502, content={"detail": str(erro)})

    def exigir_chave_api(x_api_key: Annotated[str, Header()] = "") -> None:
        if not secrets.compare_digest(x_api_key.encode(), configuracoes.chave_api.encode()):
            raise HTTPException(status_code=401, detail="X-Api-Key inválida.")

    @app.get("/saude")
    def saude() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/v1/validacoes", dependencies=[Depends(exigir_chave_api)])
    def validar(pedido: PedidoValidacao) -> ResultadoValidacao:
        # rota síncrona de propósito: o FastAPI a roda numa thread e o CLIP (CPU) não trava o event loop
        try:
            dados = baixar_foto(
                pedido.url_foto,
                hosts_permitidos=configuracoes.conjunto_hosts_permitidos,
                tamanho_maximo=configuracoes.tamanho_maximo_bytes,
                cliente=cliente_http,
            )
            imagem = abrir_imagem(dados)
        except FotoInvalida as erro:
            return rejeitar_foto(pedido.categoria, str(erro))
        predicao = classificador.classificar(imagem)  # a tupla (classe_detectada, probabilidade)
        return decidir_pertinencia(pedido.categoria, predicao, configuracoes.limiar_confianca)

    return app
```

Por que a fábrica recebe as dependências: os testes injetam um `ClassificadorFalso` e um storage de mentira (`httpx.MockTransport`), então rodam em milissegundos sem rede e sem modelo.

- [ ] **Step 5: Rodar e ver passar**

Run: `pytest tests/test_configuracoes.py tests/test_app.py -v`
Expected: `10 passed`

Run: `pytest`
Expected: `43 passed, 1 deselected` (pode aparecer 1 warning de depreciação do Starlette sobre o httpx no TestClient; não afeta nada)

- [ ] **Step 6: Criar o `.env.example` e o `.env` local**

`.env.example`:

```
# Copie para .env (cp .env.example .env). Valores para desenvolvimento local.
# Em produção (k3s) estas variáveis vêm do Deployment e de um Secret.

# Valor do header X-Api-Key. Produção: python -c "import secrets; print(secrets.token_urlsafe(32))"
CHAVE_API=dev-chave-local-troque-em-producao

# Hosts de onde o serviço aceita baixar fotos, separados por vírgula (anti-SSRF).
# Local: localhost (python -m http.server). Produção: o host do storage onde o app salva as fotos.
HOSTS_PERMITIDOS=localhost

LIMIAR_CONFIANCA=0.5
TAMANHO_MAXIMO_BYTES=10000000
TIMEOUT_DOWNLOAD_SEGUNDOS=10
MODELO_CLIP=openai/clip-vit-base-patch32
```

```bash
cp .env.example .env
```

- [ ] **Step 7: Smoke test com o CLIP de verdade**

Um `http.server` local faz o papel do storage. Coloque uma foto **sua** de garrafa PET em `amostras/plastico/garrafa.jpg` (a pasta `amostras/` já está no `.gitignore`).

```bash
# terminal 1 — o "storage"
mkdir -p amostras/plastico
python -m http.server 9000 --directory amostras

# terminal 2 — o validador (carrega o CLIP em alguns segundos)
uvicorn validador_fotos.app:criar_app --factory --port 8000

# terminal 3
curl -s -X POST http://localhost:8000/v1/validacoes \
  -H "X-Api-Key: dev-chave-local-troque-em-producao" \
  -H "Content-Type: application/json" \
  -d '{"url_foto": "http://localhost:9000/plastico/garrafa.jpg", "categoria": "Plástico"}'
```

Expected: `"pertinente": true`, `"categoria_detectada": "plastico"` e `"mensagem": "Foto compatível com plástico."` para uma foto nítida. Repita com `"categoria": "Vidro"`: `"pertinente": false` e a mensagem dizendo o que o modelo viu. Pelo navegador, `http://localhost:8000/docs` faz o mesmo. Pare os servidores com Ctrl+C.

- [ ] **Step 8: Commit**

```bash
git add validador_fotos/configuracoes.py validador_fotos/app.py tests/test_configuracoes.py tests/test_app.py .env.example
git commit -m "feat: API HTTP de validação com chave de API e mapeamento de erros"
```

---

### Task 7: Container + README (contrato, integração e deploy)

**Files:**
- Create: `.dockerignore`, `Dockerfile`, `README.md`

**Interfaces:**
- Consumes: `criar_app` (Task 6) via `uvicorn validador_fotos.app:criar_app --factory`; as variáveis de `Configuracoes` (Task 6).
- Produces: imagem `ecociente-validador-fotos` ouvindo na porta 8000, com o CLIP embutido; `README.md` com o contrato que o serviço de postagens e a infra vão seguir.

- [ ] **Step 1: Escrever `.dockerignore` e `Dockerfile`**

`.dockerignore`:

```
.venv
.git
__pycache__
.pytest_cache
.env
amostras
tests
docs
```

`Dockerfile`:

```dockerfile
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app

# torch só CPU: a roda padrão do PyPI no Linux traz CUDA (vários GB) e o cluster não tem GPU
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

RUN useradd --create-home app
USER app
ENV HF_HOME=/home/app/.cache/huggingface

# baixa o CLIP durante o build: o pod sobe sem depender do Hugging Face
ARG MODELO_CLIP=openai/clip-vit-base-patch32
ENV MODELO_CLIP=${MODELO_CLIP}
RUN python -c "from transformers import pipeline; pipeline('zero-shot-image-classification', model='${MODELO_CLIP}')"
ENV HF_HUB_OFFLINE=1

COPY --chown=app validador_fotos ./validador_fotos

EXPOSE 8000
CMD ["uvicorn", "validador_fotos.app:criar_app", "--factory", "--host", "0.0.0.0", "--port", "8000"]
```

- [ ] **Step 2: Build**

Run: `docker build -t ecociente-validador-fotos .`
Expected: termina sem erro (o passo que baixa o CLIP demora alguns minutos na primeira vez).

- [ ] **Step 3: Subir e conferir**

```bash
docker run -d --name validador --env-file .env -p 8000:8000 ecociente-validador-fotos
docker logs -f validador   # espere "Application startup complete" e saia com Ctrl+C
curl -s http://localhost:8000/saude
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8000/v1/validacoes \
  -H "Content-Type: application/json" -d '{"url_foto": "http://localhost/x.jpg", "categoria": "vidro"}'
docker stats --no-stream validador
docker rm -f validador
```

Expected: `{"status":"ok"}`; depois `401` (pedido sem `X-Api-Key`); o `docker stats` mostra a coluna MEM USAGE — anote o valor e passe para quem mantém o `devops-infra-ecociente`.

- [ ] **Step 4: Escrever o README**

`README.md`:

````markdown
# Validador de Fotos — EcoCiente

Microsserviço que recebe a URL da foto de uma postagem e a categoria escolhida pelo morador, roda um modelo de visão (CLIP zero-shot, só CPU) e responde se a foto é pertinente. É a triagem automática de `tb_postagens` (`triagem_automatica_aprovada` / `triagem_automatica_confianca`): auxiliar — a votação comunitária continua decidindo.

## Rodar local

```bash
python -m venv .venv && source .venv/Scripts/activate   # Linux/macOS: source .venv/bin/activate
pip install -r requirements-dev.txt
cp .env.example .env
uvicorn validador_fotos.app:criar_app --factory --reload   # a 1ª vez baixa o CLIP (~600 MB)
```

Documentação interativa: http://localhost:8000/docs

## Testes

```bash
pytest             # unitários, sem modelo (segundos)
pytest -m modelo   # carrega o CLIP de verdade
```

## Contrato

`POST /v1/validacoes` com o header `X-Api-Key`:

```json
{"url_foto": "https://<host do storage>/postagens/42.jpg", "categoria": "Plástico"}
```

`categoria` é o `nome_categoria` de `tb_lkp_categorias_residuos`; acento, maiúsculas e espaços são ignorados. Aceitas: papel, plastico, vidro, metal, organico.

Resposta 200:

```json
{"pertinente": false, "categoria_informada": "plastico", "categoria_detectada": "vidro", "confianca": 87.42, "mensagem": "A foto parece conter vidro, não plástico."}
```

| Status | Quando | O que o chamador faz |
|---|---|---|
| 200 | veredito — inclusive arquivo que não é imagem ou grande demais (`pertinente: false`, `categoria_detectada` e `confianca` nulos) | grava `triagem_automatica_aprovada = pertinente` e `triagem_automatica_confianca = confianca`; mostra `mensagem` ao morador |
| 401 | `X-Api-Key` errada ou ausente | erro de configuração: não grava nada |
| 422 | categoria fora do catálogo, URL malformada ou host fora de `HOSTS_PERMITIDOS` | erro de integração: não grava (fica `NULL` = não processada) e registra no log |
| 502 | o storage não entregou a foto (fora do ar, timeout, 404) | não grava; tenta de novo mais tarde, poucas vezes |

Chame depois do `INSERT` da postagem, fora da transação, com timeout de uns 30 s (download + inferência em CPU; pedidos simultâneos entram em fila).

## Variáveis de ambiente

| Variável | Padrão | Para quê |
|---|---|---|
| `CHAVE_API` | obrigatória (≥ 16 caracteres) | valor esperado no header `X-Api-Key` |
| `HOSTS_PERMITIDOS` | obrigatória | hosts do storage das fotos, separados por vírgula (anti-SSRF) |
| `LIMIAR_CONFIANCA` | `0.5` | probabilidade mínima da categoria para aprovar |
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
````

- [ ] **Step 5: Commit**

```bash
git add .dockerignore Dockerfile README.md
git commit -m "build: imagem Docker com CLIP embutido e README com contrato e deploy"
```

---

### Task 8: Calibração com fotos reais

**Files:**
- Create: `validador_fotos/calibracao.py`
- Test: `tests/test_calibracao.py`
- Modify: `README.md` (acrescenta a seção Calibração no fim), `.env.example` (valor de `LIMIAR_CONFIANCA`)

**Interfaces:**
- Consumes: `CATEGORIAS` (Task 1); `abrir_imagem`, `FotoInvalida` (Tasks 3–4); `MODELO_PADRAO`, `ClassificadorClip` (Task 5).
- Produces:
  - `taxas(resultados: list[tuple[str, str, float]], limiar: float) -> tuple[float, float]` — `resultados = [(classe_real, classe_detectada, probabilidade)]`; devolve `(aprova_corretas %, aprovaria_errada %)`.
  - CLI: `python -m validador_fotos.calibracao <pasta>`.

- [ ] **Step 1: Escrever o teste que falha**

`tests/test_calibracao.py`:

```python
import pytest

from validador_fotos.calibracao import taxas


def test_taxas_separa_acertos_de_aprovacoes_indevidas():
    resultados = [
        ("plastico", "plastico", 0.90),  # aprovada no limiar 0.5
        ("plastico", "plastico", 0.40),  # categoria certa, mas abaixo do limiar
        ("vidro", "plastico", 0.70),  # passaria como plástico: aprovação indevida
        ("nao_residuo", "metal", 0.60),  # selfie que passaria como metal
        ("nao_residuo", "nao_residuo", 0.95),
    ]
    aprova_corretas, aprovaria_errada = taxas(resultados, limiar=0.5)
    assert aprova_corretas == pytest.approx(100 * 1 / 3)
    assert aprovaria_errada == pytest.approx(100 * 2 / 5)


def test_taxas_sem_fotos_nao_divide_por_zero():
    assert taxas([], limiar=0.5) == (0.0, 0.0)
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `pytest tests/test_calibracao.py -v`
Expected: erro de coleta `ModuleNotFoundError: No module named 'validador_fotos.calibracao'`

- [ ] **Step 3: Implementar**

`validador_fotos/calibracao.py`:

```python
"""Mede o classificador em fotos reais para escolher o LIMIAR_CONFIANCA.

Uso:   python -m validador_fotos.calibracao amostras
Pastas: amostras/<slug da categoria>/*.jpg  e  amostras/nao_residuo/*.jpg (selfies, pets, prints...)
"""
import sys
from pathlib import Path

from validador_fotos.categorias import CATEGORIAS
from validador_fotos.classificador import MODELO_PADRAO, ClassificadorClip
from validador_fotos.foto import FotoInvalida, abrir_imagem

EXTENSOES = {".jpg", ".jpeg", ".png", ".webp", ".heic"}
LIMIARES = (0.3, 0.4, 0.5, 0.6, 0.7, 0.8)


def taxas(resultados: list[tuple[str, str, float]], limiar: float) -> tuple[float, float]:
    """resultados = [(classe_real, classe_detectada, probabilidade)]. Devolve, em %:

    - aprova_corretas: fotos de resíduo aprovadas na categoria certa (quanto maior, melhor);
    - aprovaria_errada: fotos que passariam como OUTRA categoria com confiança >= limiar (quanto menor, melhor).
    """
    residuos = [r for r in resultados if r[0] in CATEGORIAS]
    corretas = sum(1 for real, detectada, p in residuos if detectada == real and p >= limiar)
    erradas = sum(1 for real, detectada, p in resultados if detectada in CATEGORIAS and detectada != real and p >= limiar)
    return 100 * corretas / max(len(residuos), 1), 100 * erradas / max(len(resultados), 1)


def main(pasta: str) -> None:
    fotos = sorted(p for p in Path(pasta).glob("*/*") if p.suffix.lower() in EXTENSOES)
    if not fotos:
        sys.exit(f"Nenhuma foto em {pasta}/<categoria>/")
    classificador = ClassificadorClip(MODELO_PADRAO)
    resultados = []
    for caminho in fotos:
        try:
            imagem = abrir_imagem(caminho.read_bytes())
        except FotoInvalida as erro:
            print(f"--- {caminho}: pulada ({erro})")
            continue
        detectada, probabilidade = classificador.classificar(imagem)
        resultados.append((caminho.parent.name, detectada, probabilidade))
        marca = "ok " if detectada == caminho.parent.name else "ERR"
        print(f"{marca} {caminho}: {detectada} ({probabilidade:.0%})")
    acertos = sum(real == detectada for real, detectada, _ in resultados)
    print(f"\n{len(resultados)} fotos | acurácia: {acertos / max(len(resultados), 1):.1%}\n")
    print("limiar | aprova_corretas | aprovaria_errada")
    for limiar in LIMIARES:
        corretas, erradas = taxas(resultados, limiar)
        print(f"  {limiar:.1f}  |     {corretas:5.1f}%      |     {erradas:5.1f}%")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "amostras")
```

- [ ] **Step 4: Rodar e ver passar**

Run: `pytest tests/test_calibracao.py -v`
Expected: `2 passed`

Run: `pytest`
Expected: `45 passed, 1 deselected`

- [ ] **Step 5: Juntar as fotos de calibração**

Com o celular, nas condições reais do app (luz de casa, lixo na mão ou na pia), tire ~20 fotos por pasta:

```
amostras/papel/  amostras/plastico/  amostras/vidro/  amostras/metal/  amostras/organico/
amostras/nao_residuo/   ← selfies, pets, prints de tela, foto escura
```

O nome de cada pasta tem que ser o slug da classe (é ele que o script usa como "resposta certa").

- [ ] **Step 6: Rodar a calibração**

Run: `python -m validador_fotos.calibracao amostras`
Expected: uma linha `ok`/`ERR` por foto, depois a acurácia geral e a tabela `limiar | aprova_corretas | aprovaria_errada` para 0.3–0.8.

- [ ] **Step 7: Escolher o limiar**

Regra: o **menor** limiar com `aprovaria_errada` ≤ 5%. Se nenhum chegar lá, ou se a acurácia ficar abaixo de ~70%, olhe as linhas `ERR`, reescreva ou acrescente frases em `categorias.py` para as classes que mais erram (descrevendo como as fotos reais delas são) e rode de novo; depois `pytest` para confirmar que nada quebrou. Ponha o valor escolhido em `LIMIAR_CONFIANCA` no `.env.example` (e depois no Deployment).

- [ ] **Step 8: Documentar no README**

Acrescente no fim do `README.md`:

````markdown
## Calibração

Fotos reais em `amostras/<categoria>/` (papel, plastico, vidro, metal, organico) e `amostras/nao_residuo/` (selfies, pets, prints), ~20 por pasta, tiradas com celular. Depois:

```bash
python -m validador_fotos.calibracao amostras
```

`aprova_corretas` = % das fotos honestas que seriam aprovadas; `aprovaria_errada` = % das fotos que passariam como outra categoria. Use em `LIMIAR_CONFIANCA` o menor limiar com `aprovaria_errada` ≤ 5%.
````

- [ ] **Step 9: Commit**

```bash
git add validador_fotos/calibracao.py tests/test_calibracao.py README.md .env.example validador_fotos/categorias.py
git commit -m "feat: calibração do limiar com fotos reais"
```
