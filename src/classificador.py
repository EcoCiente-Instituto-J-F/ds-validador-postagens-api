"""Modelo de visão. Contrato: classificar(imagem) -> (classe_detectada, probabilidade 0–1)."""
import threading
from typing import Protocol

from PIL import Image

from src.categorias import TODAS_AS_CLASSES

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
