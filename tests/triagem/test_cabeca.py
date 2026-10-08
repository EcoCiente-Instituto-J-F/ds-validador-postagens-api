import numpy as np
import pytest
from PIL import Image

from src.visao.cabeca import CabecaTreinada, ClassificadorCabeca, treinar_cabeca

CLASSES = ["metal", "papel", "vidro"]


def amostras(por_classe: int = 40, dim: int = 32, semente: int = 0):
    """Três nuvens bem separadas no espaço de embeddings, como seriam fotos de três categorias."""
    rng = np.random.default_rng(semente)
    centros = rng.normal(0, 3, (3, dim))
    vetores = np.vstack([centros[i] + rng.normal(0, 1, (por_classe, dim)) for i in range(3)])
    rotulos = [classe for classe in CLASSES for _ in range(por_classe)]
    return vetores, rotulos


def test_cabeca_aprende_e_acerta_em_amostras_novas():
    cabeca = treinar_cabeca(*amostras(semente=0), modelo="clip-a")
    rng = np.random.default_rng(99)  # mesmos centros do treino (semente 0), ruído novo
    centros = np.random.default_rng(0).normal(0, 3, (3, 32))
    teste = np.vstack([centros[i] + rng.normal(0, 1, (20, 32)) for i in range(3)])
    previstos = [cabeca.ranking(v)[0][0] for v in teste]
    esperados = [classe for classe in CLASSES for _ in range(20)]
    assert np.mean([p == e for p, e in zip(previstos, esperados)]) >= 0.95


def test_ranking_e_distribuicao_ordenada_que_soma_1():
    cabeca = treinar_cabeca(*amostras(), modelo="clip-a")
    ranking = cabeca.ranking(amostras()[0][0])
    assert sorted(p for _, p in ranking) == [p for _, p in ranking][::-1]
    assert sum(p for _, p in ranking) == pytest.approx(1.0)
    assert {c for c, _ in ranking} == set(CLASSES)


def test_salvar_e_carregar_da_o_mesmo_resultado(tmp_path):
    cabeca = treinar_cabeca(*amostras(), modelo="clip-a")
    cabeca.salvar(tmp_path / "cabeca.npz")
    carregada = CabecaTreinada.carregar(tmp_path / "cabeca.npz")
    vetor = amostras()[0][5]
    assert carregada.ranking(vetor) == pytest.approx(cabeca.ranking(vetor))
    assert carregada.modelo == "clip-a"  # o arquivo lembra com qual CLIP foi treinado


def test_carregar_recusa_classe_fora_do_catalogo(tmp_path):
    cabeca = treinar_cabeca(*amostras(), modelo="clip-a")
    cabeca.classes = ["metal", "papel", "eletronico"]
    cabeca.salvar(tmp_path / "ruim.npz")
    with pytest.raises(ValueError, match="eletronico"):
        CabecaTreinada.carregar(tmp_path / "ruim.npz")


def test_treino_exige_pelo_menos_duas_classes():
    with pytest.raises(ValueError, match="duas classes"):
        treinar_cabeca(np.zeros((4, 8)), ["vidro"] * 4, modelo="clip-a")


def test_classificador_com_cabeca_usa_o_extrator_de_embeddings():
    cabeca = treinar_cabeca(*amostras(), modelo="clip-a")

    class ExtratorFalso:
        def embedding(self, imagem: Image.Image) -> np.ndarray:
            return amostras()[0][0]  # vetor da 1ª amostra de "metal"

    classificador = ClassificadorCabeca(ExtratorFalso(), cabeca)
    classe, probabilidade = classificador.classificar(Image.new("RGB", (8, 8)))
    assert classe == "metal"
    assert 0.0 < probabilidade <= 1.0
