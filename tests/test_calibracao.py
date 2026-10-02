import pytest

from src.calibracao import taxas


def test_taxas_separa_acertos_de_aprovacoes_indevidas():
    resultados = [
        ("plastico", "plastico", 0.90),  # aprovada no limiar 0.5
        ("plastico", "plastico", 0.40),  # categoria certa, mas abaixo do limiar
        ("vidro", "plastico", 0.70),  # passaria como plástico: aprovação indevida
        ("nao_residuo", "metal", 0.60),  # selfie que passaria como metal
        ("nao_residuo", "nao_residuo", 0.95),
    ]
    aprova_corretas, aprovaria_errada = taxas(resultados, limiar=0.5)
    assert aprova_corretas == pytest.approx(100 * 1 / 3)
    assert aprovaria_errada == pytest.approx(100 * 2 / 5)


def test_taxas_sem_fotos_nao_divide_por_zero():
    assert taxas([], limiar=0.5) == (0.0, 0.0)
