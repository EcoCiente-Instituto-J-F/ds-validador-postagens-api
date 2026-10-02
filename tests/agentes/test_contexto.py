from atomic_agents.context import SystemPromptGenerator

from src.agentes.contexto import TriagensFotosCtx
from src.dominio.pertinencia import decidir_pertinencia
from src.dominio.schemas import rejeitar_foto


def test_sem_triagens_avisa_que_nada_foi_analisado():
    assert TriagensFotosCtx().get_info() == "Nenhuma foto foi analisada nesta conversa."


def test_lista_veredito_e_falha_com_a_mensagem_para_o_morador():
    ctx = TriagensFotosCtx()
    ctx.registrar(decidir_pertinencia("plastico", [("vidro", 0.8)], 0.5))
    ctx.registrar(rejeitar_foto("metal", "O storage respondeu HTTP 503.", status="foto_indisponivel"))
    assert ctx.get_info() == (
        "- Foto de plastico: não pertinente; o modelo viu vidro com 80% de confiança. "
        "A foto parece conter vidro, não plástico.\n"
        "- Foto de metal: não analisada (foto_indisponivel). O storage respondeu HTTP 503."
    )


def test_guarda_so_as_5_ultimas_triagens():
    ctx = TriagensFotosCtx()
    for categoria in ["papel", "plastico", "vidro", "metal", "organico", "papel"]:
        ctx.registrar(rejeitar_foto(categoria, "O arquivo não é uma imagem válida."))
    linhas = ctx.get_info().splitlines()
    assert len(linhas) == 5
    assert linhas[0].startswith("- Foto de plastico")  # a primeira (papel) saiu


def test_secao_aparece_no_prompt_do_agente():
    ctx = TriagensFotosCtx()
    ctx.registrar(decidir_pertinencia("vidro", [("vidro", 0.9)], 0.5))
    prompt = SystemPromptGenerator(context_providers={"triagens_fotos": ctx}).generate_prompt()
    assert "## Triagens recentes de fotos\n- Foto de vidro: pertinente" in prompt


def test_revisao_aparece_como_sem_veredito_automatico():
    ctx = TriagensFotosCtx()
    ctx.registrar(decidir_pertinencia("vidro", [("vidro", 0.4)], 0.5, limiar_revisao=0.3))
    assert "- Foto de vidro: aguardando revisão humana; o modelo viu vidro com 40% de confiança." in ctx.get_info()
