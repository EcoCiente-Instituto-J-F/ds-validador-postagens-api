import httpx

from tests.duble_instructor import ClienteInstructorFalso
from src.agentes.contexto import TriagensFotosCtx
from src.agentes.explicador import (
    PerguntaMoradorInput,
    RespostaMoradorOutput,
    criar_explicador,
    triar_e_responder,
)
from src.triagem.schemas import ValidarFotoInput
from src.triagem.tool import ValidarFotoConfig, ValidarFotoTool


def ClienteFalso() -> ClienteInstructorFalso:  # noqa: N802
    return ClienteInstructorFalso(RespostaMoradorOutput(resposta="Sua foto de plástico foi aprovada."))


def test_agente_tem_os_schemas_e_o_contexto_de_triagens():
    cliente = ClienteFalso()
    agente = criar_explicador(cliente, "modelo-qualquer", TriagensFotosCtx())
    assert agente.input_schema is PerguntaMoradorInput
    assert agente.output_schema is RespostaMoradorOutput
    assert "triagens_fotos" in agente.system_prompt_generator.context_providers


def test_triar_e_responder_poe_o_veredito_no_prompt(png_valido, classificador_falso):
    cliente = ClienteFalso()
    triagens = TriagensFotosCtx()
    agente = criar_explicador(cliente, "modelo-qualquer", triagens)
    config = ValidarFotoConfig(_env_file=None, hosts_permitidos="storage.exemplo.com")
    storage = httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(200, content=png_valido)))
    ferramenta = ValidarFotoTool(config, classificador_falso, storage)

    resposta = triar_e_responder(
        ferramenta,
        triagens,
        agente,
        ValidarFotoInput(url_foto="https://storage.exemplo.com/1.png", categoria="plastico"),
        "Minha foto passou?",
    )

    assert resposta.resposta == "Sua foto de plástico foi aprovada."
    sistema = cliente.chamadas[0]["messages"][0]["content"]
    assert "## Triagens recentes de fotos\n- Foto de plastico: pertinente" in sistema
    assert cliente.chamadas[0]["response_model"] is RespostaMoradorOutput


def test_provedor_diferente_ajusta_papel_do_assistente():
    agente = criar_explicador(ClienteFalso(), "gemini-2.5-flash", TriagensFotosCtx(), assistant_role="model")
    assert agente.assistant_role == "model"
