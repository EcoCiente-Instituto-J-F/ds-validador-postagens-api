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
