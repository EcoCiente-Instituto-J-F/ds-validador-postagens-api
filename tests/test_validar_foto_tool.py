import httpx
import pytest
from pydantic import ValidationError

from src.validar_foto_tool import (
    ValidarFotoConfig,
    ValidarFotoInput,
    ValidarFotoOutput,
    ValidarFotoTool,
    decidir_pertinencia,
    rejeitar_foto,
)

LIMIAR = 0.5


# ---------- contrato (schemas) ----------

def test_input_normaliza_a_categoria_vinda_do_banco():
    pedido = ValidarFotoInput(url_foto="https://storage.exemplo.com/1.jpg", categoria=" Plástico ")
    assert pedido.categoria == "plastico"


def test_input_recusa_categoria_fora_do_catalogo():
    with pytest.raises(ValidationError, match="categoria desconhecida"):
        ValidarFotoInput(url_foto="https://storage.exemplo.com/1.jpg", categoria="Eletrônico")


@pytest.mark.parametrize("schema", [ValidarFotoInput, ValidarFotoOutput])
def test_schema_explica_tudo_para_o_llm(schema):
    json_schema = schema.model_json_schema()
    assert json_schema["description"]
    assert all(campo.get("description") for campo in json_schema["properties"].values())


# ---------- regra de pertinência ----------

def test_mesma_categoria_acima_do_limiar_e_pertinente():
    resultado = decidir_pertinencia("plastico", ("plastico", 0.8734), LIMIAR)
    assert resultado.status == "ok"
    assert resultado.pertinente is True
    assert resultado.categoria_detectada == "plastico"
    assert resultado.confianca == 87.34
    assert resultado.mensagem == "Foto compatível com plástico."


def test_confianca_exatamente_no_limiar_e_pertinente():
    assert decidir_pertinencia("vidro", ("vidro", 0.5), LIMIAR).pertinente is True


def test_mesma_categoria_abaixo_do_limiar_nao_e_pertinente():
    resultado = decidir_pertinencia("vidro", ("vidro", 0.31), LIMIAR)
    assert resultado.pertinente is False
    assert resultado.mensagem.startswith("Não deu para confirmar que a foto é de vidro")


def test_outra_categoria_nao_e_pertinente_e_diz_o_que_viu():
    resultado = decidir_pertinencia("plastico", ("vidro", 0.9), LIMIAR)
    assert resultado.pertinente is False
    assert resultado.categoria_detectada == "vidro"
    assert resultado.mensagem == "A foto parece conter vidro, não plástico."


def test_foto_sem_residuo_nao_e_pertinente():
    resultado = decidir_pertinencia("metal", ("nao_residuo", 0.95), LIMIAR)
    assert resultado.pertinente is False
    assert resultado.mensagem == "A foto não parece mostrar um resíduo."


def test_confianca_vira_0_a_100_com_duas_casas():
    assert decidir_pertinencia("papel", ("papel", 0.123456), LIMIAR).confianca == 12.35


def test_rejeitar_foto_nao_tem_classe_nem_confianca():
    resultado = rejeitar_foto("organico", "O arquivo não é uma imagem válida.")
    assert resultado.model_dump() == {
        "status": "ok",
        "pertinente": False,
        "categoria_informada": "organico",
        "categoria_detectada": None,
        "confianca": None,
        "mensagem": "O arquivo não é uma imagem válida.",
    }


def test_rejeitar_foto_tambem_monta_falha_tipada():
    resultado = rejeitar_foto("vidro", "O storage respondeu HTTP 503.", status="foto_indisponivel")
    assert (resultado.status, resultado.pertinente) == ("foto_indisponivel", False)


# ---------- ValidarFotoTool ----------

URL_FOTO = "https://storage.exemplo.com/postagens/42.png"


def montar_tool(conteudo: bytes, classificador, status_storage: int = 200) -> ValidarFotoTool:
    config = ValidarFotoConfig(_env_file=None, hosts_permitidos="storage.exemplo.com")
    storage = httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(status_storage, content=conteudo)))
    return ValidarFotoTool(config, classificador, storage)


def test_tool_devolve_o_veredito_do_modelo(png_valido, classificador_falso):
    ferramenta = montar_tool(png_valido, classificador_falso)
    resultado = ferramenta.run(ValidarFotoInput(url_foto=URL_FOTO, categoria="plastico"))
    assert (resultado.status, resultado.pertinente, resultado.confianca) == ("ok", True, 91.23)
    assert classificador_falso.imagens[0].mode == "RGB"


def test_tool_transforma_url_recusada_em_falha_tipada(png_valido, classificador_falso):
    ferramenta = montar_tool(png_valido, classificador_falso)
    resultado = ferramenta.run(ValidarFotoInput(url_foto="https://169.254.169.254/x", categoria="vidro"))
    assert (resultado.status, resultado.pertinente) == ("pedido_invalido", False)
    assert classificador_falso.imagens == []


def test_tool_transforma_storage_fora_do_ar_em_falha_tipada(png_valido, classificador_falso):
    ferramenta = montar_tool(png_valido, classificador_falso, status_storage=503)
    resultado = ferramenta.run(ValidarFotoInput(url_foto=URL_FOTO, categoria="vidro"))
    assert resultado.status == "foto_indisponivel"
    assert "503" in resultado.mensagem


def test_tool_transforma_arquivo_invalido_em_veredito(classificador_falso):
    ferramenta = montar_tool(b"<html>Access Denied</html>", classificador_falso)
    resultado = ferramenta.run(ValidarFotoInput(url_foto=URL_FOTO, categoria="vidro"))
    assert (resultado.status, resultado.pertinente, resultado.categoria_detectada) == ("ok", False, None)


def test_tool_expoe_seus_schemas_para_o_agente(png_valido, classificador_falso):
    ferramenta = montar_tool(png_valido, classificador_falso)
    assert ferramenta.input_schema is ValidarFotoInput
    assert ferramenta.output_schema is ValidarFotoOutput
    assert ferramenta.tool_description.startswith("Pede a triagem automática")


def test_nome_e_descricao_da_tool_nao_vem_do_ambiente(monkeypatch, png_valido, classificador_falso):
    monkeypatch.setenv("TITLE", "titulo de outro sistema")
    monkeypatch.setenv("DESCRIPTION", "descrição de outro sistema")
    ferramenta = montar_tool(png_valido, classificador_falso)
    assert ferramenta.tool_name == "ValidarFotoInput"
    assert ferramenta.tool_description.startswith("Pede a triagem automática")


def test_config_le_variaveis_de_ambiente(monkeypatch):
    monkeypatch.setenv("HOSTS_PERMITIDOS", " Storage.Exemplo.com , cdn.exemplo.com ,")
    monkeypatch.setenv("LIMIAR_CONFIANCA", "0.65")
    config = ValidarFotoConfig(_env_file=None)
    assert config.conjunto_hosts_permitidos == frozenset({"storage.exemplo.com", "cdn.exemplo.com"})
    assert config.limiar_confianca == 0.65
    assert config.tamanho_maximo_bytes == 10_000_000
    assert config.modelo_clip == "openai/clip-vit-base-patch32"
