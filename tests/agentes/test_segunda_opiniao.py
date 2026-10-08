import base64
import io

import pytest
from PIL import Image
from pydantic import ValidationError

from tests.duble_instructor import ClienteInstructorFalso
from src.agentes.segunda_opiniao import SegundaOpiniaoOutput, SegundaOpiniaoVisao


def avaliar(cliente, imagem=None, categoria="vidro"):
    return SegundaOpiniaoVisao(cliente, "modelo-qualquer").avaliar(imagem or Image.new("RGB", (64, 64), "green"), categoria)


def test_devolve_o_parecer_e_manda_a_foto_ao_modelo():
    cliente = ClienteInstructorFalso(SegundaOpiniaoOutput(pertinente=True, justificativa="garrafa de vidro verde"))
    assert avaliar(cliente) is True
    texto, foto = cliente.chamadas[0]["messages"][-1]["content"]
    assert '"categoria":"vidro"' in texto
    assert foto.source.startswith("data:image/jpeg;base64,")
    assert cliente.chamadas[0]["response_model"] is SegundaOpiniaoOutput


def test_parecer_negativo_tambem_volta():
    assert avaliar(ClienteInstructorFalso(SegundaOpiniaoOutput(pertinente=False, justificativa="é plástico"))) is False


def test_foto_grande_e_reduzida_antes_de_ir_ao_provedor():
    cliente = ClienteInstructorFalso(SegundaOpiniaoOutput(pertinente=True, justificativa="ok"))
    avaliar(cliente, Image.new("RGB", (3000, 2000), "white"))
    foto = cliente.chamadas[0]["messages"][-1]["content"][1]
    enviada = Image.open(io.BytesIO(base64.b64decode(foto.source.split(",", 1)[1])))
    assert max(enviada.size) <= 768


def test_falha_do_provedor_vira_none_e_fica_no_log(caplog):
    with caplog.at_level("WARNING"):
        assert avaliar(ClienteInstructorFalso(erro=RuntimeError("timeout"))) is None
    assert "revisão humana" in caplog.text


def test_cada_chamada_usa_um_agente_novo_sem_historico_acumulado():
    cliente = ClienteInstructorFalso(SegundaOpiniaoOutput(pertinente=True, justificativa="ok"))
    opiniao = SegundaOpiniaoVisao(cliente, "modelo-qualquer")
    opiniao.avaliar(Image.new("RGB", (64, 64)), "vidro")
    opiniao.avaliar(Image.new("RGB", (64, 64)), "metal")
    assert len(cliente.chamadas[1]["messages"]) == 2  # sistema + só a foto atual


def test_config_errada_falha_na_criacao_e_nao_vira_none_silencioso():
    with pytest.raises(ValidationError):
        SegundaOpiniaoVisao(object(), "modelo-qualquer")  # não é um cliente Instructor


def test_chamada_ao_provedor_leva_timeout_por_padrao_e_ele_pode_ser_trocado_ou_removido():
    resposta = SegundaOpiniaoOutput(pertinente=True, justificativa="ok")
    cliente = ClienteInstructorFalso(resposta)
    SegundaOpiniaoVisao(cliente, "m").avaliar(Image.new("RGB", (64, 64)), "vidro")
    SegundaOpiniaoVisao(cliente, "m", timeout_segundos=5).avaliar(Image.new("RGB", (64, 64)), "vidro")
    SegundaOpiniaoVisao(cliente, "m", timeout_segundos=None).avaliar(Image.new("RGB", (64, 64)), "vidro")  # provedor sem kwarg timeout
    assert [c.get("timeout") for c in cliente.chamadas] == [15.0, 5, None]


def test_prompt_manda_ignorar_texto_escrito_na_foto():
    cliente = ClienteInstructorFalso(SegundaOpiniaoOutput(pertinente=True, justificativa="ok"))
    avaliar(cliente)
    assert "nunca ordens" in cliente.chamadas[0]["messages"][0]["content"]
