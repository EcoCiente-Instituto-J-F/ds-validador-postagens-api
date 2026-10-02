import numpy as np
import pytest

from src.calibracao.treinar import avaliar


def dados(por_classe: int = 30, dim: int = 24):
    rng = np.random.default_rng(1)
    centros = rng.normal(0, 3, (2, dim))
    rotulos = [classe for classe in ("vidro", "metal") for _ in range(por_classe)]
    embeddings = np.vstack([centros[i] + rng.normal(0, 1, (por_classe, dim)) for i in range(2)])
    zeroshot = ["vidro"] * 60  # um zero-shot ruim: sempre diz vidro
    return rotulos, embeddings, zeroshot


def test_avaliar_compara_cabeca_e_zero_shot_na_mesma_fatia_de_teste():
    rotulos, embeddings, zeroshot = dados()
    relatorio = avaliar(rotulos, embeddings, zeroshot, modelo="clip-a")
    assert relatorio.n_teste == 12  # 1 em cada 5 fica de fora do treino
    assert relatorio.acuracia_cabeca >= 0.9
    assert relatorio.acuracia_zero_shot == pytest.approx(0.5)
    assert sorted(relatorio.cabeca_final.classes) == ["metal", "vidro"]  # a final é treinada com todas as fotos


def test_avaliar_exige_fotos_suficientes():
    with pytest.raises(ValueError, match="fotos"):
        avaliar(["vidro", "metal"], np.zeros((2, 4)), ["vidro", "metal"], modelo="clip-a")


def test_pasta_fora_do_catalogo_e_recusada_antes_de_treinar():
    rotulos, embeddings, zeroshot = dados()
    rotulos[0] = "plastic"  # pasta com erro de digitação
    with pytest.raises(ValueError, match="plastic"):
        avaliar(rotulos, embeddings, zeroshot, modelo="clip-a")
