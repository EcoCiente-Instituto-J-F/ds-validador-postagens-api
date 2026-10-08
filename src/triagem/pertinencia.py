"""Regra de pertinência: junta a categoria escolhida com o ranking do modelo."""
from src.triagem.categorias import CATEGORIAS, NAO_RESIDUO
from src.triagem.schemas import Alternativa, ValidarFotoOutput

ALTERNATIVAS_MAX = 2


def decidir_pertinencia(
    categoria_informada: str,
    ranking: list[tuple[str, float]],
    limiar: float,
    limiar_revisao: float | None = None,
) -> ValidarFotoOutput:
    """ranking = [(classe, probabilidade 0–1)] da mais para a menos provável; o 1º item é a tupla do modelo.

    Categoria certa com probabilidade em [limiar_revisao, limiar) não é recusada nem aprovada: pertinente=None
    (revisão humana). Sem limiar_revisao não existe zona cinza.
    """
    detectada, probabilidade = ranking[0]
    informada = CATEGORIAS[categoria_informada].nome
    pertinente: bool | None
    if detectada == NAO_RESIDUO.slug:
        pertinente, mensagem = False, "A foto não parece mostrar um resíduo."
    elif detectada != categoria_informada:
        pertinente, mensagem = False, f"A foto parece conter {CATEGORIAS[detectada].nome}, não {informada}."
    elif probabilidade >= limiar:
        pertinente, mensagem = True, f"Foto compatível com {informada}."
    elif limiar_revisao is not None and probabilidade >= limiar_revisao:
        pertinente = None
        mensagem = f"Não deu para confirmar que a foto é de {informada}; ela vai para revisão."
    else:
        pertinente = False
        mensagem = f"Não deu para confirmar que a foto é de {informada}. Tente de mais perto e com boa luz."
    return ValidarFotoOutput(
        status="ok",
        pertinente=pertinente,
        categoria_informada=categoria_informada,
        categoria_detectada=detectada,
        confianca=round(probabilidade * 100, 2),
        alternativas=[Alternativa(categoria=c, confianca=round(p * 100, 2)) for c, p in ranking[1 : 1 + ALTERNATIVAS_MAX]],
        mensagem=mensagem,
    )
