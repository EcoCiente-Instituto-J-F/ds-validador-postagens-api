import io

import numpy as np
import pytest
from PIL import Image


@pytest.fixture
def png_valido() -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", (8, 8), (200, 30, 30)).save(buffer, format="PNG")
    return buffer.getvalue()


@pytest.fixture
def png_de_tela() -> bytes:
    """512x512 com listras de 4 px sobre ruído: o tipo de padrão que a grade de pixels de uma tela deixa."""
    rng = np.random.default_rng(0)
    colunas = np.arange(512)
    matriz = 128 + rng.normal(0, 20, (512, 512)) + 40 * np.sin(2 * np.pi * colunas / 4)[None, :]
    buffer = io.BytesIO()
    Image.fromarray(np.clip(matriz, 0, 255).astype("uint8")).convert("RGB").save(buffer, format="PNG")
    return buffer.getvalue()


class ClassificadorFalso:
    """Dublê do modelo: devolve a tupla configurada e guarda as imagens que recebeu."""

    def __init__(self, predicao: tuple[str, float] = ("plastico", 0.9123), outros: list[tuple[str, float]] | None = None) -> None:
        self.predicao = predicao
        self.outros = outros or []  # as demais classes do ranking, da mais para a menos provável
        self.imagens: list[Image.Image] = []

    def ranking(self, imagem: Image.Image) -> list[tuple[str, float]]:
        self.imagens.append(imagem)
        return [self.predicao, *self.outros]

    def classificar(self, imagem: Image.Image) -> tuple[str, float]:
        return self.ranking(imagem)[0]


@pytest.fixture
def classificador_falso() -> ClassificadorFalso:
    return ClassificadorFalso()
