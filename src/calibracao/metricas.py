"""Métricas da calibração: não dependem do modelo, então testam sem baixar o CLIP."""
from src.triagem.categorias import CATEGORIAS


def taxas(resultados: list[tuple[str, str, float]], limiar: float) -> tuple[float, float]:
    """resultados = [(classe_real, classe_detectada, probabilidade)]. Devolve, em %:

    - aprova_corretas: fotos de resíduo aprovadas na categoria certa (quanto maior, melhor);
    - aprovaria_errada: fotos que passariam como OUTRA categoria com confiança >= limiar (quanto menor, melhor).
    """
    residuos = [r for r in resultados if r[0] in CATEGORIAS]
    corretas = sum(1 for real, detectada, p in residuos if detectada == real and p >= limiar)
    erradas = sum(1 for real, detectada, p in resultados if detectada in CATEGORIAS and detectada != real and p >= limiar)
    return 100 * corretas / max(len(residuos), 1), 100 * erradas / max(len(resultados), 1)


def taxas_da_categoria(resultados: list[tuple[str, str, float]], categoria: str, limiar: float) -> tuple[float, float]:
    """Mesma ideia de taxas(), olhando só uma categoria: serve para escolher limiares_por_categoria.

    - aprova_corretas: % das fotos reais da categoria que seriam aprovadas nela;
    - aprovaria_errada: % das fotos de OUTRAS classes que passariam como essa categoria.
    """
    dela = [r for r in resultados if r[0] == categoria]
    de_fora = [r for r in resultados if r[0] != categoria]
    corretas = sum(1 for _, detectada, p in dela if detectada == categoria and p >= limiar)
    erradas = sum(1 for _, detectada, p in de_fora if detectada == categoria and p >= limiar)
    return 100 * corretas / max(len(dela), 1), 100 * erradas / max(len(de_fora), 1)
