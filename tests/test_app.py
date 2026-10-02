import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from src.app import Configuracoes, criar_app
from src.validar_foto_tool import ValidarFotoTool

CHAVE = "chave-de-teste-com-mais-de-16"
URL_FOTO = "https://storage.exemplo.com/postagens/42.png"


def montar(conteudo: bytes, classificador, status_storage: int = 200) -> TestClient:
    configuracoes = Configuracoes(_env_file=None, chave_api=CHAVE, hosts_permitidos="storage.exemplo.com")
    storage = httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(status_storage, content=conteudo)))
    return TestClient(criar_app(configuracoes, ValidarFotoTool(configuracoes, classificador, storage)))


def validar(cliente: TestClient, categoria="Plástico", url=URL_FOTO, chave=CHAVE):
    return cliente.post("/v1/validacoes", json={"url_foto": url, "categoria": categoria}, headers={"X-Api-Key": chave})


def test_foto_pertinente_devolve_veredito_completo(png_valido, classificador_falso):
    resposta = validar(montar(png_valido, classificador_falso))
    assert resposta.status_code == 200
    assert resposta.json() == {
        "status": "ok",
        "pertinente": True,
        "categoria_informada": "plastico",
        "categoria_detectada": "plastico",
        "confianca": 91.23,
        "mensagem": "Foto compatível com plástico.",
    }


def test_foto_de_outra_categoria_nao_e_pertinente(png_valido, classificador_falso):
    classificador_falso.predicao = ("vidro", 0.8)
    corpo = validar(montar(png_valido, classificador_falso)).json()
    assert corpo["pertinente"] is False
    assert corpo["mensagem"] == "A foto parece conter vidro, não plástico."


def test_arquivo_que_nao_e_imagem_vira_veredito_nao_pertinente(classificador_falso):
    resposta = validar(montar(b"<html>Access Denied</html>", classificador_falso))
    assert resposta.status_code == 200
    assert resposta.json() == {
        "status": "ok",
        "pertinente": False,
        "categoria_informada": "plastico",
        "categoria_detectada": None,
        "confianca": None,
        "mensagem": "O arquivo não é uma imagem válida.",
    }
    assert classificador_falso.imagens == []


def test_chave_api_errada_ou_ausente_retorna_401(png_valido, classificador_falso):
    cliente = montar(png_valido, classificador_falso)
    assert validar(cliente, chave="errada").status_code == 401
    assert cliente.post("/v1/validacoes", json={"url_foto": URL_FOTO, "categoria": "vidro"}).status_code == 401


def test_categoria_fora_do_catalogo_retorna_422(png_valido, classificador_falso):
    resposta = validar(montar(png_valido, classificador_falso), categoria="Eletrônico")
    assert resposta.status_code == 422
    assert "categoria desconhecida" in resposta.text


def test_host_nao_permitido_retorna_422_sem_baixar(png_valido, classificador_falso):
    resposta = validar(montar(png_valido, classificador_falso), url="https://169.254.169.254/latest/meta-data/")
    assert resposta.status_code == 422
    assert "Host não permitido" in resposta.json()["detail"]
    assert classificador_falso.imagens == []


def test_storage_fora_do_ar_retorna_502(png_valido, classificador_falso):
    resposta = validar(montar(png_valido, classificador_falso, status_storage=503))
    assert resposta.status_code == 502
    assert resposta.json() == {"detail": "O storage respondeu HTTP 503."}


def test_saude(classificador_falso):
    assert montar(b"", classificador_falso).get("/saude").json() == {"status": "ok"}


def test_chave_api_curta_e_recusada():
    with pytest.raises(ValidationError):
        Configuracoes(_env_file=None, chave_api="curta", hosts_permitidos="storage.exemplo.com")
