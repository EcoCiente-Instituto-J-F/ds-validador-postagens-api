"""Mede o classificador em fotos reais para escolher o LIMIAR_CONFIANCA.

Uso:   python -m src.calibracao amostras [modelo_clip]   (rode com outro CLIP para comparar modelos)
Pastas: amostras/<slug da categoria>/*.jpg  e  amostras/nao_residuo/*.jpg (selfies, pets, prints...)
"""
import sys
from pathlib import Path

from src.calibracao.metricas import taxas, taxas_da_categoria
from src.dominio.categorias import CATEGORIAS
from src.visao.classificador import MODELO_PADRAO, ClassificadorClip
from src.visao.foto import EXTENSOES, FotoInvalida, abrir_imagem

LIMIARES = (0.3, 0.4, 0.5, 0.6, 0.7, 0.8)


def main(pasta: str, modelo: str = MODELO_PADRAO) -> None:
    fotos = sorted(p for p in Path(pasta).glob("*/*") if p.suffix.lower() in EXTENSOES)
    if not fotos:
        sys.exit(f"Nenhuma foto em {pasta}/<categoria>/")
    classificador = ClassificadorClip(modelo)
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
    print("\nPor categoria (para LIMIARES_POR_CATEGORIA): limiar -> aprova_corretas / aprovaria_errada")
    for categoria in CATEGORIAS:
        celulas = (f"{limiar:.1f}: {a:.0f}%/{b:.0f}%" for limiar in LIMIARES for a, b in [taxas_da_categoria(resultados, categoria, limiar)])
        print(f"  {categoria:9} " + "  ".join(celulas))


if __name__ == "__main__":
    main(*(sys.argv[1:3] or ["amostras"]))
