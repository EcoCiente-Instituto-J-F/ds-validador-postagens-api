"""Contrato da tool: o que entra e o que sai (também é o que o LLM enxerga)."""
from typing import Literal

from atomic_agents import BaseIOSchema
from pydantic import Field, field_validator

from src.dominio.categorias import CATEGORIAS, normalizar_categoria

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


class Alternativa(BaseIOSchema):
    """Uma das outras classes que o modelo considerou para a foto."""

    categoria: str = Field(..., description="Slug da classe (uma categoria ou nao_residuo).")
    confianca: float = Field(..., ge=0, le=100, description="Confiança do modelo nessa classe, 0–100 com 2 casas.")


class ValidarFotoOutput(BaseIOSchema):
    """Veredito da triagem automática da foto, ou falha tipada quando a foto não pôde ser analisada."""

    status: Status = Field(
        ...,
        description="ok = veredito válido; pedido_invalido = URL recusada; foto_indisponivel = o storage não entregou a foto.",
    )
    pertinente: bool | None = Field(
        ...,
        description=(
            "True se a foto combina com a categoria informada; False se não combina; "
            "None quando não há veredito automático (status != 'ok' ou foto na zona de revisão humana)."
        ),
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
    hash_foto: str | None = Field(
        default=None,
        description="Hash perceptual da foto (16 hex): o app compara com o das postagens anteriores para achar foto repetida.",
    )
    sinais: list[str] = Field(
        default_factory=list,
        description=(
            "Indícios informativos, sem efeito no veredito: 'padrao_de_tela' (moiré, mais forte) e 'sem_exif' "
            "(fraco: apps de mensagem também removem EXIF). Não foram calibrados com fotos reais."
        ),
    )
    segunda_opiniao: bool = Field(
        default=False, description="True quando o veredito veio da segunda opinião do modelo de visão e linguagem."
    )
    alternativas: list[Alternativa] = Field(
        default_factory=list, description="As próximas classes mais prováveis depois da detectada (até 2)."
    )
    mensagem: str = Field(..., description="Explicação curta em PT-BR para mostrar ao morador.")


def rejeitar_foto(categoria_informada: str, motivo: str, status: Status = "ok") -> ValidarFotoOutput:
    """Resultado sem passar pelo modelo: arquivo inválido (status ok = veredito False) ou falha tipada (sem veredito)."""
    pertinente = False if status == "ok" else None
    return ValidarFotoOutput(
        status=status, pertinente=pertinente, categoria_informada=categoria_informada, mensagem=motivo
    )
