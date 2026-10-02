"""Treina a cabeça sobre os embeddings do CLIP e diz se ela vence o zero-shot, na mesma fatia de teste.

Uso:   python -m src.calibracao.treinar amostras cabeca.npz [modelo_clip]
Pastas: as mesmas da calibração (amostras/<categoria>/*.jpg e amostras/nao_residuo/*.jpg).
Só ligue em produção (CABECA_TREINADA=cabeca.npz) se a acurácia da cabeça passar a do zero-shot com folga.
"""
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np

from src.visao.cabeca import SLUGS, CabecaTreinada, treinar_cabeca
from src.visao.classificador import MODELO_PADRAO, ClassificadorClip
from src.visao.foto import EXTENSOES, FotoInvalida, abrir_imagem

MIN_FOTOS = 20


@dataclass
class Relatorio:
    n_teste: int
    acuracia_cabeca: float
    acuracia_zero_shot: float
    cabeca_final: CabecaTreinada  # treinada com todas as fotos


def avaliar(rotulos: list[str], embeddings: np.ndarray, zero_shot: list[str], modelo: str) -> Relatorio:
    desconhecidas = set(rotulos) - SLUGS
    if desconhecidas:  # pasta com erro de digitação ou .thumbs: melhor falhar antes de treinar
        raise ValueError(f"Pastas fora do catálogo: {', '.join(sorted(desconhecidas))}.")
    if len(rotulos) < MIN_FOTOS:
        raise ValueError(f"Poucas fotos para treinar: {len(rotulos)} (mínimo {MIN_FOTOS}).")
    indices = np.arange(len(rotulos))
    teste, treino = indices[indices % 5 == 0], indices[indices % 5 != 0]  # 1 em cada 5 fica de fora, em todas as classes
    cabeca = treinar_cabeca(embeddings[treino], [rotulos[i] for i in treino], modelo)
    acertos_cabeca = [cabeca.ranking(embeddings[i])[0][0] == rotulos[i] for i in teste]
    acertos_zero_shot = [zero_shot[i] == rotulos[i] for i in teste]
    return Relatorio(len(teste), float(np.mean(acertos_cabeca)), float(np.mean(acertos_zero_shot)), treinar_cabeca(embeddings, rotulos, modelo))


def main(pasta: str, saida: str, modelo: str = MODELO_PADRAO) -> None:
    fotos = sorted(p for p in Path(pasta).glob("*/*") if p.suffix.lower() in EXTENSOES)
    clip = ClassificadorClip(modelo)
    rotulos, vetores, zero_shot = [], [], []
    for caminho in fotos:
        try:
            imagem = abrir_imagem(caminho.read_bytes())
        except FotoInvalida as erro:
            print(f"--- {caminho}: pulada ({erro})")
            continue
        rotulos.append(caminho.parent.name)
        vetores.append(clip.embedding(imagem))
        zero_shot.append(clip.classificar(imagem)[0])
    if not vetores:
        sys.exit(f"Nenhuma foto válida em {pasta}/<categoria>/")
    relatorio = avaliar(rotulos, np.vstack(vetores), zero_shot, modelo)
    print(f"{len(rotulos)} fotos; teste com {relatorio.n_teste}")
    print(f"acurácia no teste: cabeça {relatorio.acuracia_cabeca:.1%} | zero-shot {relatorio.acuracia_zero_shot:.1%}")
    relatorio.cabeca_final.salvar(saida)
    print(f"cabeça final (todas as fotos) salva em {saida}")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    main(*sys.argv[1:4])
