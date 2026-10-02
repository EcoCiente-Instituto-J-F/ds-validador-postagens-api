import pytest
from PIL import Image

from src.categorias import TODAS_AS_CLASSES
from src.classificador import MODELO_PADRAO, ClassificadorClip, agregar_por_classe

CLASSE_DO_PROMPT = {
    "a photo of a plastic bottle": "plastico",
    "a photo of plastic bags": "plastico",
    "a photo of a glass jar": "vidro",
    "a selfie": "nao_residuo",
}


def test_soma_os_prompts_da_mesma_classe():
    saida = [  # formato do pipeline zero-shot do transformers, já ordenado por score
        {"label": "a photo of a glass jar", "score": 0.40},
        {"label": "a photo of a plastic bottle", "score": 0.35},
        {"label": "a photo of plastic bags", "score": 0.20},
        {"label": "a selfie", "score": 0.05},
    ]
    classe, probabilidade = agregar_por_classe(saida, CLASSE_DO_PROMPT)
    assert classe == "plastico"  # nenhum prompt de plástico venceu sozinho, mas a soma vence
    assert probabilidade == pytest.approx(0.55)


def test_devolve_a_tupla_classe_probabilidade():
    assert agregar_por_classe([{"label": "a selfie", "score": 1.0}], CLASSE_DO_PROMPT) == ("nao_residuo", 1.0)


@pytest.mark.modelo
def test_clip_de_verdade_devolve_classe_do_catalogo():
    classificador = ClassificadorClip(MODELO_PADRAO)
    classe, probabilidade = classificador.classificar(Image.new("RGB", (224, 224), "white"))
    assert classe in {c.slug for c in TODAS_AS_CLASSES}
    assert 0.0 <= probabilidade <= 1.0
