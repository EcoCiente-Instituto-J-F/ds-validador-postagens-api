import json
import threading
from concurrent.futures import ThreadPoolExecutor
import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from src.api.app import Configuracoes, criar_app
from src.tools.validar_foto_tool import ValidarFotoTool

CHAVE = "chave-de-teste-com-mais-de-16"
URL_FOTO = "https://storage.exemplo.com/postagens/42.png"


def montar(conteudo: bytes, classificador, status_storage: int = 200, **config) -> TestClient:
    configuracoes = Configuracoes(_env_file=None, chave_api=CHAVE, hosts_permitidos="storage.exemplo.com", **config)
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
        "hash_foto": "0000000000000000",
        "sinais": ["sem_exif"],
        "segunda_opiniao": False,
        "alternativas": [],
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
        "hash_foto": None,
        "sinais": [],
        "segunda_opiniao": False,
        "alternativas": [],
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


# ---------- rotação de chave, log e métricas ----------

def test_chave_anterior_vale_durante_a_rotacao(png_valido, classificador_falso):
    configuracoes = Configuracoes(
        _env_file=None, chave_api=CHAVE, chave_api_anterior="chave-antiga-com-mais-de-16", hosts_permitidos="storage.exemplo.com"
    )
    storage = httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(200, content=png_valido)))
    cliente = TestClient(criar_app(configuracoes, ValidarFotoTool(configuracoes, classificador_falso, storage)))
    assert validar(cliente, chave=CHAVE).status_code == 200
    assert validar(cliente, chave="chave-antiga-com-mais-de-16").status_code == 200
    assert validar(cliente, chave="outra-chave-qualquer-0000").status_code == 401


def test_sem_chave_anterior_a_chave_vazia_nao_passa(png_valido, classificador_falso):
    assert validar(montar(png_valido, classificador_falso), chave="").status_code == 401


def test_cada_veredito_vira_uma_linha_de_log_sem_a_url(png_valido, classificador_falso, caplog):
    with caplog.at_level("INFO", logger="validador_fotos.veredito"):
        validar(montar(png_valido, classificador_falso))
    registro = json.loads(caplog.records[-1].getMessage())
    assert registro["categoria_informada"] == "plastico"
    assert registro["categoria_detectada"] == "plastico"
    assert registro["confianca"] == 91.23
    assert registro["pertinente"] is True
    assert registro["hash_foto"] == "0000000000000000"
    assert "url" not in json.dumps(registro)  # a URL pode carregar token de URL assinada


def test_metricas_contam_vereditos_e_confianca(png_valido, classificador_falso):
    cliente = montar(png_valido, classificador_falso)
    validar(cliente)
    classificador_falso.predicao = ("vidro", 0.8)
    validar(cliente)
    texto = cliente.get("/metrics").text
    assert 'validacoes_total{categoria="plastico",pertinente="sim",status="ok"} 1.0' in texto
    assert 'validacoes_total{categoria="plastico",pertinente="nao",status="ok"} 1.0' in texto
    assert "confianca_modelo_count" in texto
    assert "validacao_segundos_count 2.0" in texto


def test_variavel_vazia_no_ambiente_conta_como_ausente(monkeypatch):
    # depois da rotação o operador costuma deixar CHAVE_API_ANTERIOR= em branco; o serviço não pode deixar de subir
    monkeypatch.setenv("CHAVE_API", CHAVE)
    monkeypatch.setenv("HOSTS_PERMITIDOS", "storage.exemplo.com")
    monkeypatch.setenv("CHAVE_API_ANTERIOR", "")
    monkeypatch.setenv("LIMIAR_REVISAO", "")
    configuracoes = Configuracoes(_env_file=None)
    assert configuracoes.chave_api_anterior is None
    assert configuracoes.limiar_revisao is None


# ---------- vazão: limite de pedidos e URL assinada ----------

def test_pedidos_acima_do_limite_recebem_503_com_retry_after(png_valido):
    entrou, liberar = threading.Event(), threading.Event()

    class Lento:
        def ranking(self, imagem):
            entrou.set()
            liberar.wait(5)
            return [("plastico", 0.9)]

    cliente = montar(png_valido, Lento(), max_pedidos_simultaneos=1)
    with ThreadPoolExecutor(1) as pool:
        primeiro = pool.submit(validar, cliente)
        assert entrou.wait(5)
        segundo = validar(cliente)  # a única vaga está ocupada pelo primeiro
        liberar.set()
        assert primeiro.result().status_code == 200
    assert segundo.status_code == 503
    assert segundo.headers["retry-after"] == "5"
    assert 'pedidos_rejeitados_total 1.0' in cliente.get("/metrics").text
    assert validar(cliente).status_code == 200  # a vaga foi devolvida


def test_a_vaga_volta_mesmo_quando_o_pedido_falha(png_valido, classificador_falso):
    cliente = montar(png_valido, classificador_falso, status_storage=503, max_pedidos_simultaneos=1)
    assert [validar(cliente).status_code for _ in range(3)] == [502, 502, 502]


def test_url_assinada_com_query_e_aceita_e_nao_vai_para_o_log(png_valido, classificador_falso, caplog):
    url = "https://storage.exemplo.com/postagens/42.png?X-Amz-Signature=segredo123&X-Amz-Expires=300"
    with caplog.at_level("INFO", logger="validador_fotos.veredito"):
        resposta = validar(montar(png_valido, classificador_falso), url=url)
    assert resposta.status_code == 200
    assert "segredo123" not in caplog.text
