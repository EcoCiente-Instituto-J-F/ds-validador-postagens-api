"""Indícios de que a foto é de uma tela. Só informam: não mudam o veredito e NÃO foram calibrados com fotos reais."""
import io

import numpy as np
from PIL import Image

LADO = 256  # recorte central analisado
RAIO_MIN, RAIO_MAX = 24, 120  # faixa de frequências onde a grade de pixels de uma tela aparece
LIMIAR_PICO = 30.0  # quantas vezes o pico passa da média do próprio anel de frequência


def tem_padrao_de_tela(imagem: Image.Image) -> bool:
    """Procura um pico isolado de frequência (moiré da grade de pixels). Textura regular de verdade (tecido,
    persiana, tijolo) também dispara; por isso é indício, não prova.
    """
    largura, altura = imagem.size
    if min(largura, altura) < LADO:
        return False  # pequena demais para julgar
    esquerda, topo = (largura - LADO) // 2, (altura - LADO) // 2
    recorte = np.asarray(imagem.convert("L").crop((esquerda, topo, esquerda + LADO, topo + LADO)), dtype=float)
    recorte = (recorte - recorte.mean()) * np.outer(np.hanning(LADO), np.hanning(LADO))
    espectro = np.abs(np.fft.fftshift(np.fft.fft2(recorte)))
    y, x = np.indices(espectro.shape)
    raio = np.hypot(y - LADO // 2, x - LADO // 2).astype(int)
    media_do_anel = np.bincount(raio.ravel(), espectro.ravel()) / np.maximum(np.bincount(raio.ravel()), 1)
    banda = (raio >= RAIO_MIN) & (raio <= RAIO_MAX)
    razao = espectro[banda] / np.maximum(media_do_anel[raio[banda]], 1e-9)
    return bool(razao.max() > LIMIAR_PICO)


def sem_exif(dados: bytes) -> bool:
    """Foto de câmera traz EXIF; print, download e foto reenviada por apps costumam não trazer (também é indício fraco)."""
    try:
        return not Image.open(io.BytesIO(dados)).getexif()
    except Exception:  # arquivo inválido: já é tratado em abrir_imagem
        return False
