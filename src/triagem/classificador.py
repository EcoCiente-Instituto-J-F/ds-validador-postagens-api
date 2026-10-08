"""Modelo de visão. Contrato: classificar(imagem) -> (classe_detectada, probabilidade 0–1); ranking() traz todas as classes."""
import threading
from typing import Protocol

import numpy as np
from PIL import Image

from src.dominio.categorias import TODAS_AS_CLASSES

MODELO_PADRAO = "openai/clip-vit-base-patch32"


class Classificador(Protocol):
    """Qualquer modelo serve (CLIP, uma CNN treinada pela equipe...) desde que devolva a tupla."""

    def ranking(self, imagem: Image.Image) -> list[tuple[str, float]]: ...

    def classificar(self, imagem: Image.Image) -> tuple[str, float]: ...


def agregar_por_classe(saida: list[dict], classe_do_prompt: dict[str, str]) -> list[tuple[str, float]]:
    """Soma as probabilidades dos prompts de cada classe e ordena da mais para a menos provável."""
    totais: dict[str, float] = {}
    for item in saida:
        classe = classe_do_prompt[item["label"]]
        totais[classe] = totais.get(classe, 0.0) + item["score"]
    return sorted(totais.items(), key=lambda par: par[1], reverse=True)


class ClassificadorClip:
    """Zero-shot com CLIP: compara a foto com as descrições de categorias.py, sem dataset nem treino."""

    def __init__(self, nome_modelo: str = MODELO_PADRAO) -> None:
        from transformers import pipeline  # import pesado (torch): só quando o modelo real é usado

        self._pipeline = pipeline("zero-shot-image-classification", model=nome_modelo)
        self._classe_do_prompt = {p: classe.slug for classe in TODAS_AS_CLASSES for p in classe.prompts}
        self._trava = threading.Lock()  # o FastAPI roda rotas síncronas em várias threads ao mesmo tempo

    def ranking(self, imagem: Image.Image) -> list[tuple[str, float]]:
        with self._trava:
            saida = self._pipeline(imagem, candidate_labels=list(self._classe_do_prompt), hypothesis_template="{}")
        return agregar_por_classe(saida, self._classe_do_prompt)

    def classificar(self, imagem: Image.Image) -> tuple[str, float]:
        return self.ranking(imagem)[0]

    def embedding(self, imagem: Image.Image) -> np.ndarray:
        """Vetor da imagem no espaço do CLIP (entrada da cabeça treinada). Só testado com `pytest -m modelo`."""
        import torch  # noqa: PLC0415  (import pesado: só quando o modelo real é usado)

        with self._trava, torch.no_grad():
            entradas = self._pipeline.image_processor(imagem, return_tensors="pt")
            saida = self._pipeline.model.get_image_features(**entradas)
        if hasattr(saida, "pooler_output"):  # versões novas do transformers devolvem um objeto, não o tensor
            saida = saida.pooler_output
        return saida[0].numpy()
