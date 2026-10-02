import pytest
from PIL import Image

from src.dominio.categorias import TODAS_AS_CLASSES
from src.visao.classificador import MODELO_PADRAO, ClassificadorClip, agregar_por_classe

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
    ranking = agregar_por_classe(saida, CLASSE_DO_PROMPT)
    assert [classe for classe, _ in ranking] == ["plastico", "vidro", "nao_residuo"]  # a soma vence, nenhum prompt venceu sozinho
    assert [p for _, p in ranking] == pytest.approx([0.55, 0.40, 0.05])


def test_ranking_de_uma_classe_so():
    assert agregar_por_classe([{"label": "a selfie", "score": 1.0}], CLASSE_DO_PROMPT) == [("nao_residuo", 1.0)]


@pytest.mark.modelo
def test_clip_de_verdade_devolve_classe_do_catalogo():
    classificador = ClassificadorClip(MODELO_PADRAO)
    ranking = classificador.ranking(Image.new("RGB", (224, 224), "white"))
    assert {classe for classe, _ in ranking} == {c.slug for c in TODAS_AS_CLASSES}
    assert sum(p for _, p in ranking) == pytest.approx(1.0, abs=1e-3)
    assert classificador.classificar(Image.new("RGB", (224, 224), "white")) == ranking[0]


@pytest.mark.modelo
def test_clip_de_verdade_devolve_embedding_do_tamanho_do_modelo():
    vetor = ClassificadorClip(MODELO_PADRAO).embedding(Image.new("RGB", (224, 224), "white"))
    assert vetor.shape == (512,)  # CLIP ViT-B/32
