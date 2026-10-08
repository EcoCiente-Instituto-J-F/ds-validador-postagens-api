"""Cabeça treinada: regressão logística sobre os embeddings do CLIP, treinada com as fotos rotuladas do app.

Em geral supera o zero-shot, mas só vale com dados reais: treine com python -m src.calibracao.treinar.
Em produção roda só com numpy (sem scikit-learn): é uma multiplicação de matrizes.
"""
from pathlib import Path
from typing import Protocol

import numpy as np
from PIL import Image

from src.triagem.categorias import TODAS_AS_CLASSES

SLUGS = {classe.slug for classe in TODAS_AS_CLASSES}


def _softmax(logits: np.ndarray) -> np.ndarray:
    e = np.exp(logits - logits.max(axis=-1, keepdims=True))
    return e / e.sum(axis=-1, keepdims=True)


class CabecaTreinada:
    def __init__(
        self, classes: list[str], pesos: np.ndarray, vies: np.ndarray, media: np.ndarray, desvio: np.ndarray, modelo: str
    ) -> None:
        self.classes, self.pesos, self.vies, self.media, self.desvio = classes, pesos, vies, media, desvio
        self.modelo = modelo  # CLIP que gerou os embeddings do treino: com outro modelo as probabilidades não fazem sentido

    def ranking(self, vetor: np.ndarray) -> list[tuple[str, float]]:
        probabilidades = _softmax(((vetor - self.media) / self.desvio) @ self.pesos.T + self.vies)
        return sorted(zip(self.classes, probabilidades.tolist()), key=lambda par: par[1], reverse=True)

    def salvar(self, caminho: Path | str) -> None:
        np.savez(caminho, classes=np.array(self.classes), pesos=self.pesos, vies=self.vies, media=self.media, desvio=self.desvio, modelo=np.array(self.modelo))

    @classmethod
    def carregar(cls, caminho: Path | str) -> "CabecaTreinada":
        with np.load(caminho, allow_pickle=False) as arquivo:
            classes = [str(c) for c in arquivo["classes"]]
            desconhecidas = set(classes) - SLUGS
            if desconhecidas:
                raise ValueError(f"A cabeça tem classes fora do catálogo: {', '.join(sorted(desconhecidas))}.")
            return cls(classes, arquivo["pesos"], arquivo["vies"], arquivo["media"], arquivo["desvio"], str(arquivo["modelo"]))


def treinar_cabeca(
    embeddings: np.ndarray, rotulos: list[str], modelo: str, passos: int = 300, taxa: float = 0.1, l2: float = 1e-2
) -> CabecaTreinada:
    """Regressão logística multinomial por gradiente em lote, com L2 (poucas fotos para muitas dimensões)."""
    classes = sorted(set(rotulos))
    if len(classes) < 2:
        raise ValueError("O treino precisa de pelo menos duas classes.")
    media, desvio = embeddings.mean(axis=0), embeddings.std(axis=0) + 1e-6
    x = (embeddings - media) / desvio
    alvo = np.array([[rotulo == classe for classe in classes] for rotulo in rotulos], dtype=float)
    pesos, vies = np.zeros((len(classes), x.shape[1])), np.zeros(len(classes))
    for _ in range(passos):
        erro = (_softmax(x @ pesos.T + vies) - alvo) / len(x)
        pesos -= taxa * (erro.T @ x + l2 * pesos)
        vies -= taxa * erro.sum(axis=0)
    return CabecaTreinada(classes, pesos, vies, media, desvio, modelo)


class ExtratorDeEmbeddings(Protocol):
    def embedding(self, imagem: Image.Image) -> np.ndarray: ...


class ClassificadorCabeca:
    """Mesmo contrato do ClassificadorClip (ranking/classificar), usando a cabeça no lugar dos prompts."""

    def __init__(self, extrator: ExtratorDeEmbeddings, cabeca: CabecaTreinada) -> None:
        self._extrator, self._cabeca = extrator, cabeca

    def ranking(self, imagem: Image.Image) -> list[tuple[str, float]]:
        return self._cabeca.ranking(self._extrator.embedding(imagem))

    def classificar(self, imagem: Image.Image) -> tuple[str, float]:
        return self.ranking(imagem)[0]
