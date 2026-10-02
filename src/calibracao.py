"""Mede o classificador em fotos reais para escolher o LIMIAR_CONFIANCA.

Uso:   python -m validador_fotos.calibracao amostras
Pastas: amostras/<slug da categoria>/*.jpg  e  amostras/nao_residuo/*.jpg (selfies, pets, prints...)
"""
import sys
from pathlib import Path

from src.categorias import CATEGORIAS
from src.classificador import MODELO_PADRAO, ClassificadorClip
from src.foto import FotoInvalida, abrir_imagem

EXTENSOES = {".jpg", ".jpeg", ".png", ".webp", ".heic"}
LIMIARES = (0.3, 0.4, 0.5, 0.6, 0.7, 0.8)


def taxas(resultados: list[tuple[str, str, float]], limiar: float) -> tuple[float, float]:
    """resultados = [(classe_real, classe_detectada, probabilidade)]. Devolve, em %:

    - aprova_corretas: fotos de resíduo aprovadas na categoria certa (quanto maior, melhor);
    - aprovaria_errada: fotos que passariam como OUTRA categoria com confiança >= limiar (quanto menor, melhor).
    """
    residuos = [r for r in resultados if r[0] in CATEGORIAS]
    corretas = sum(1 for real, detectada, p in residuos if detectada == real and p >= limiar)
    erradas = sum(1 for real, detectada, p in resultados if detectada in CATEGORIAS and detectada != real and p >= limiar)
    return 100 * corretas / max(len(residuos), 1), 100 * erradas / max(len(resultados), 1)


def main(pasta: str) -> None:
    fotos = sorted(p for p in Path(pasta).glob("*/*") if p.suffix.lower() in EXTENSOES)
    if not fotos:
        sys.exit(f"Nenhuma foto em {pasta}/<categoria>/")
    classificador = ClassificadorClip(MODELO_PADRAO)
    resultados = []
    for caminho in fotos:
        try:
            imagem = abrir_imagem(caminho.read_bytes())
        except FotoInvalida as erro:
            print(f"--- {caminho}: pulada ({erro})")
            continue
        detectada, probabilidade = classificador.classificar(imagem)
        resultados.append((caminho.parent.name, detectada, probabilidade))
        marca = "ok " if detectada == caminho.parent.name else "ERR"
        print(f"{marca} {caminho}: {detectada} ({probabilidade:.0%})")
    acertos = sum(real == detectada for real, detectada, _ in resultados)
    print(f"\n{len(resultados)} fotos | acurácia: {acertos / max(len(resultados), 1):.1%}\n")
    print("limiar | aprova_corretas | aprovaria_errada")
    for limiar in LIMIARES:
        corretas, erradas = taxas(resultados, limiar)
        print(f"  {limiar:.1f}  |     {corretas:5.1f}%      |     {erradas:5.1f}%")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "amostras")
