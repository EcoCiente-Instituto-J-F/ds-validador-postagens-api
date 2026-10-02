import io

import numpy as np
from PIL import Image

from src.visao.sinais import sem_exif, tem_padrao_de_tela


def imagem_de(matriz: np.ndarray) -> Image.Image:
    return Image.fromarray(np.clip(matriz, 0, 255).astype("uint8")).convert("RGB")


def ruido(lado: int = 512, desvio: float = 20.0) -> np.ndarray:
    return 128 + np.random.default_rng(0).normal(0, desvio, (lado, lado))


def test_grade_fina_regular_parece_tela():
    colunas = np.arange(512)
    listras = 40 * np.sin(2 * np.pi * colunas / 4)  # período de 4 px: o tipo de padrão que um pixel de tela gera
    assert tem_padrao_de_tela(imagem_de(ruido() + listras[None, :])) is True


def test_ruido_de_foto_normal_nao_parece_tela():
    assert tem_padrao_de_tela(imagem_de(ruido())) is False


def test_gradiente_suave_nao_parece_tela():
    degrade = np.tile(np.linspace(0, 255, 512), (512, 1))
    assert tem_padrao_de_tela(imagem_de(degrade + ruido(desvio=3))) is False


def test_imagem_lisa_e_imagem_pequena_nao_dao_alarme():
    assert tem_padrao_de_tela(Image.new("RGB", (512, 512), "white")) is False
    assert tem_padrao_de_tela(Image.new("RGB", (64, 64), "white")) is False


def test_sem_exif_distingue_foto_de_camera_de_arquivo_sem_metadados():
    sem = io.BytesIO()
    Image.new("RGB", (8, 8)).save(sem, format="PNG")
    exif = Image.Exif()
    exif[0x010F] = "Fabricante"
    com = io.BytesIO()
    Image.new("RGB", (8, 8)).save(com, format="JPEG", exif=exif)
    assert sem_exif(sem.getvalue()) is True
    assert sem_exif(com.getvalue()) is False
    assert sem_exif(b"lixo") is False  # não é imagem: quem reclama disso é abrir_imagem, não este sinal
