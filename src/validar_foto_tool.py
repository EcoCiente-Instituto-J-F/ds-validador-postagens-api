"""Tool do Atomic Agents que valida a foto de uma postagem de reciclagem.

Contrato (schemas), regra de pertinência e a tool ficam juntos: mudam juntos.
"""
from typing import Literal

import httpx
from atomic_agents import BaseIOSchema, BaseTool
from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from src.categorias import CATEGORIAS, NAO_RESIDUO, normalizar_categoria
from src.classificador import MODELO_PADRAO, Classificador, ClassificadorClip
from src.foto import FotoIndisponivel, FotoInvalida, PedidoInvalido, abrir_imagem, baixar_foto

Status = Literal["ok", "pedido_invalido", "foto_indisponivel"]


class ValidarFotoInput(BaseIOSchema):
    """Pede a triagem automática de uma foto de reciclagem: a URL da foto e a categoria que o morador escolheu."""

    url_foto: str = Field(..., max_length=2048, description="URL http(s) da foto no storage do app.")
    categoria: str = Field(
        ...,
        description=f"Categoria escolhida pelo morador: {', '.join(CATEGORIAS)}. Acento e maiúsculas são ignorados.",
    )

    @field_validator("categoria")
    @classmethod
    def categoria_do_catalogo(cls, valor: str) -> str:
        slug = normalizar_categoria(valor)
        if slug not in CATEGORIAS:
            raise ValueError(f"categoria desconhecida; aceitas: {', '.join(CATEGORIAS)}")
        return slug


class ValidarFotoOutput(BaseIOSchema):
    """Veredito da triagem automática da foto, ou falha tipada quando a foto não pôde ser analisada."""

    status: Status = Field(
        ...,
        description="ok = veredito válido; pedido_invalido = URL recusada; foto_indisponivel = o storage não entregou a foto.",
    )
    pertinente: bool = Field(
        ..., description="True se a foto combina com a categoria informada; sempre False quando status != 'ok'."
    )
    categoria_informada: str = Field(..., description="Slug da categoria escolhida pelo morador.")
    categoria_detectada: str | None = Field(
        default=None, description="Slug da classe que o modelo viu; None quando a foto não chegou ao modelo."
    )
    confianca: float | None = Field(
        default=None,
        ge=0,
        le=100,
        description="Confiança do modelo, 0–100 com 2 casas (vai para tb_postagens.triagem_automatica_confianca).",
    )
    mensagem: str = Field(..., description="Explicação curta em PT-BR para mostrar ao morador.")


def decidir_pertinencia(categoria_informada: str, predicao: tuple[str, float], limiar: float) -> ValidarFotoOutput:
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
    return ValidarFotoOutput(
        status="ok",
        pertinente=pertinente,
        categoria_informada=categoria_informada,
        categoria_detectada=detectada,
        confianca=round(probabilidade * 100, 2),
        mensagem=mensagem,
    )


def rejeitar_foto(categoria_informada: str, motivo: str, status: Status = "ok") -> ValidarFotoOutput:
    """Resultado sem passar pelo modelo: arquivo inválido (status ok = veredito) ou falha tipada."""
    return ValidarFotoOutput(status=status, pertinente=False, categoria_informada=categoria_informada, mensagem=motivo)


class ValidarFotoConfig(BaseSettings):
    """Parâmetros da tool; o que não for passado vem das variáveis de ambiente (ou do .env).

    Não herda BaseToolConfig de propósito: os campos title/description dele seriam lidos do
    ambiente (TITLE, DESCRIPTION) e trocariam em silêncio o nome e a descrição que o LLM vê.
    """

    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    hosts_permitidos: str = Field(..., description="Hosts do storage de fotos, separados por vírgula (anti-SSRF).")
    limiar_confianca: float = Field(default=0.5, ge=0, le=1, description="Probabilidade mínima da categoria para aprovar.")
    tamanho_maximo_bytes: int = Field(default=10_000_000, gt=0, description="Tamanho máximo da foto, em bytes.")
    timeout_download_segundos: float = Field(default=10.0, gt=0, description="Timeout do download no storage.")
    modelo_clip: str = Field(
        default=MODELO_PADRAO,
        description="Modelo CLIP do Hugging Face; SigLIP não serve (usa sigmoid e quebra a soma por classe).",
    )

    @property
    def conjunto_hosts_permitidos(self) -> frozenset[str]:
        return frozenset(host.strip().lower() for host in self.hosts_permitidos.split(",") if host.strip())


class ValidarFotoTool(BaseTool[ValidarFotoInput, ValidarFotoOutput]):
    """Baixa a foto de uma postagem, roda o modelo de visão e diz se ela combina com a categoria escolhida."""

    def __init__(
        self,
        config: ValidarFotoConfig,
        classificador: Classificador | None = None,
        cliente_http: httpx.Client | None = None,
    ) -> None:
        super().__init__()  # BaseToolConfig padrão: nome e descrição da tool vêm dos schemas
        self.parametros = config
        self.classificador = classificador or ClassificadorClip(config.modelo_clip)  # carrega o CLIP uma vez
        self.cliente_http = cliente_http or httpx.Client(timeout=config.timeout_download_segundos)

    def run(self, params: ValidarFotoInput) -> ValidarFotoOutput:
        config = self.parametros
        try:
            dados = baixar_foto(
                params.url_foto,
                hosts_permitidos=config.conjunto_hosts_permitidos,
                tamanho_maximo=config.tamanho_maximo_bytes,
                cliente=self.cliente_http,
            )
            imagem = abrir_imagem(dados)
        except PedidoInvalido as erro:
            return rejeitar_foto(params.categoria, str(erro), status="pedido_invalido")
        except FotoIndisponivel as erro:
            return rejeitar_foto(params.categoria, str(erro), status="foto_indisponivel")
        except FotoInvalida as erro:
            return rejeitar_foto(params.categoria, str(erro))
        return decidir_pertinencia(params.categoria, self.classificador.classificar(imagem), config.limiar_confianca)
