from PIL import Image, ImageFilter

from src.triagem.hash_perceptual import dhash, distancia


def degrade(inverso: bool = False) -> Image.Image:
    imagem = Image.new("L", (64, 64))
    imagem.putdata([(255 - x * 4) if inverso else x * 4 for _ in range(64) for x in range(64)])
    return imagem.convert("RGB")


def test_hash_tem_16_caracteres_hex():
    assert len(dhash(degrade())) == 16
    int(dhash(degrade()), 16)


def test_mesma_foto_reduzida_e_borrada_continua_parecida():
    original = degrade()
    editada = original.resize((32, 32)).filter(ImageFilter.GaussianBlur(1))
    assert distancia(dhash(original), dhash(editada)) <= 5


def test_fotos_diferentes_ficam_longe():
    assert distancia(dhash(degrade()), dhash(degrade(inverso=True))) > 20


def test_distancia_de_hash_igual_e_zero():
    assert distancia(dhash(degrade()), dhash(degrade())) == 0
