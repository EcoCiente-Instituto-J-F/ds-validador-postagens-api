import pytest
from pydantic import ValidationError

from src.triagem.pertinencia import decidir_pertinencia
from src.triagem.schemas import ValidarFotoInput, ValidarFotoOutput, rejeitar_foto

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
    resultado = decidir_pertinencia("plastico", [("plastico", 0.8734)], LIMIAR)
    assert resultado.status == "ok"
    assert resultado.pertinente is True
    assert resultado.categoria_detectada == "plastico"
    assert resultado.confianca == 87.34
    assert resultado.mensagem == "Foto compatível com plástico."


def test_confianca_exatamente_no_limiar_e_pertinente():
    assert decidir_pertinencia("vidro", [("vidro", 0.5)], LIMIAR).pertinente is True


def test_mesma_categoria_abaixo_do_limiar_nao_e_pertinente():
    resultado = decidir_pertinencia("vidro", [("vidro", 0.31)], LIMIAR)
    assert resultado.pertinente is False
    assert resultado.mensagem.startswith("Não deu para confirmar que a foto é de vidro")


def test_outra_categoria_nao_e_pertinente_e_diz_o_que_viu():
    resultado = decidir_pertinencia("plastico", [("vidro", 0.9)], LIMIAR)
    assert resultado.pertinente is False
    assert resultado.categoria_detectada == "vidro"
    assert resultado.mensagem == "A foto parece conter vidro, não plástico."


def test_foto_sem_residuo_nao_e_pertinente():
    resultado = decidir_pertinencia("metal", [("nao_residuo", 0.95)], LIMIAR)
    assert resultado.pertinente is False
    assert resultado.mensagem == "A foto não parece mostrar um resíduo."


def test_confianca_vira_0_a_100_com_duas_casas():
    assert decidir_pertinencia("papel", [("papel", 0.123456)], LIMIAR).confianca == 12.35


def test_rejeitar_foto_nao_tem_classe_nem_confianca():
    resultado = rejeitar_foto("organico", "O arquivo não é uma imagem válida.")
    assert resultado.model_dump() == {
        "status": "ok",
        "pertinente": False,
        "categoria_informada": "organico",
        "categoria_detectada": None,
        "confianca": None,
        "hash_foto": None,
        "sinais": [],
        "segunda_opiniao": False,
        "alternativas": [],
        "mensagem": "O arquivo não é uma imagem válida.",
    }


def test_rejeitar_foto_tambem_monta_falha_tipada():
    resultado = rejeitar_foto("vidro", "O storage respondeu HTTP 503.", status="foto_indisponivel")
    assert (resultado.status, resultado.pertinente) == ("foto_indisponivel", None)  # None: não houve veredito


# ---------- zona de revisão e alternativas ----------

def test_categoria_certa_entre_os_dois_limiares_vai_para_revisao():
    resultado = decidir_pertinencia("vidro", [("vidro", 0.4)], LIMIAR, limiar_revisao=0.3)
    assert (resultado.status, resultado.pertinente) == ("ok", None)
    assert resultado.mensagem.startswith("Não deu para confirmar que a foto é de vidro")
    assert "revisão" in resultado.mensagem


def test_abaixo_do_limiar_de_revisao_continua_reprovada():
    assert decidir_pertinencia("vidro", [("vidro", 0.2)], LIMIAR, limiar_revisao=0.3).pertinente is False


def test_sem_limiar_de_revisao_nao_ha_zona_cinza():
    assert decidir_pertinencia("vidro", [("vidro", 0.4)], LIMIAR).pertinente is False


def test_aprovada_acima_do_limiar_mesmo_com_revisao_ligada():
    assert decidir_pertinencia("vidro", [("vidro", 0.6)], LIMIAR, limiar_revisao=0.3).pertinente is True


def test_alternativas_sao_as_duas_proximas_do_ranking():
    ranking = [("vidro", 0.5), ("plastico", 0.3), ("nao_residuo", 0.12), ("metal", 0.08)]
    resultado = decidir_pertinencia("plastico", ranking, LIMIAR)
    assert [(a.categoria, a.confianca) for a in resultado.alternativas] == [("plastico", 30.0), ("nao_residuo", 12.0)]
    assert resultado.categoria_detectada == "vidro"
    assert "alternativas" in ValidarFotoOutput.model_json_schema()["properties"]
