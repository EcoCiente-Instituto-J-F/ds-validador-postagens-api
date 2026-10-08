import contextlib
import json
import threading
from concurrent.futures import ThreadPoolExecutor
import httpx
import psycopg
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from src.validacao import banco
from src.api.app import Configuracoes, criar_app
from src.triagem.tool import ValidarFotoTool

CHAVE = "chave-de-teste-com-mais-de-16"
URL_FOTO = "https://storage.exemplo.com/postagens/42.png"


class BancoFalso:
    """Dublê de src.validacao.banco: devolve os dados configurados e guarda o que a API gravou."""

    def __init__(self):
        self.dados = (URL_FOTO, "Plástico")
        self.gravados = []


@pytest.fixture(autouse=True)
def banco_falso(monkeypatch):
    falso = BancoFalso()
    monkeypatch.setattr(banco, "dados_triagem", lambda conexao, id_postagem: falso.dados)
    monkeypatch.setattr(banco, "gravar_triagem", lambda conexao, *args: falso.gravados.append(args))
    return falso


def montar(conteudo: bytes, classificador, status_storage: int = 200, conectar=None, **config) -> TestClient:
    configuracoes = Configuracoes(
        _env_file=None, chave_api=CHAVE, hosts_permitidos="storage.exemplo.com", url_banco="postgresql://teste", **config
    )
    storage = httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(status_storage, content=conteudo)))
    conectar = conectar or (lambda: contextlib.nullcontext(None))
    return TestClient(criar_app(configuracoes, ValidarFotoTool(configuracoes, classificador, storage), conectar))


def validar(cliente: TestClient, chave=CHAVE):
    return cliente.post("/v1/postagens/42/triagem", headers={"X-Api-Key": chave})


def cliente_de_banco() -> TestClient:
    return montar(b"", None)


def votar(cliente: TestClient, corpo=None, chave=CHAVE):
    corpo = corpo or {"usuario_id": 7, "tipo": "aprovar"}
    return cliente.post("/v1/postagens/42/votos", json=corpo, headers={"X-Api-Key": chave})


def decidir(cliente: TestClient, chave=CHAVE):
    return cliente.post("/v1/postagens/42/decisao", json={"usuario_id": 1, "aprovar": True}, headers={"X-Api-Key": chave})


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
    assert cliente.post("/v1/postagens/42/triagem").status_code == 401


def test_host_nao_permitido_retorna_422_sem_baixar(png_valido, classificador_falso, banco_falso):
    banco_falso.dados = ("https://169.254.169.254/latest/meta-data/", "Plástico")
    resposta = validar(montar(png_valido, classificador_falso))
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
        Configuracoes(
            _env_file=None, chave_api="curta", hosts_permitidos="storage.exemplo.com", url_banco="postgresql://teste"
        )


# ---------- rotação de chave, log e métricas ----------

def test_chave_anterior_vale_durante_a_rotacao(png_valido, classificador_falso):
    configuracoes = Configuracoes(
        _env_file=None,
        chave_api=CHAVE,
        chave_api_anterior="chave-antiga-com-mais-de-16",
        hosts_permitidos="storage.exemplo.com",
        url_banco="postgresql://teste",
    )
    storage = httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(200, content=png_valido)))
    cliente = TestClient(
        criar_app(
            configuracoes, ValidarFotoTool(configuracoes, classificador_falso, storage), lambda: contextlib.nullcontext(None)
        )
    )
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
    monkeypatch.setenv("URL_BANCO", "postgresql://teste")
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


def test_url_assinada_com_query_e_aceita_e_nao_vai_para_o_log(png_valido, classificador_falso, caplog, banco_falso):
    banco_falso.dados = ("https://storage.exemplo.com/postagens/42.png?X-Amz-Signature=segredo123&X-Amz-Expires=300", "Plástico")
    with caplog.at_level("INFO", logger="validador_fotos.veredito"):
        resposta = validar(montar(png_valido, classificador_falso))
    assert resposta.status_code == 200
    assert "segredo123" not in caplog.text


# ---------- triagem grava no banco ----------

def test_triagem_grava_veredito(png_valido, classificador_falso, banco_falso):
    assert validar(montar(png_valido, classificador_falso)).status_code == 200
    assert banco_falso.gravados == [(42, True, 91.23)]


def test_triagem_nao_grava_quando_storage_falha(png_valido, classificador_falso, banco_falso):
    assert validar(montar(png_valido, classificador_falso, status_storage=503)).status_code == 502
    assert banco_falso.gravados == []


def test_conexao_fechada_durante_o_clip(png_valido):
    estado = {"abertas": 0, "aberturas": 0}

    @contextlib.contextmanager
    def conectar():
        estado["abertas"] += 1
        estado["aberturas"] += 1
        try:
            yield None
        finally:
            estado["abertas"] -= 1

    class Conferente:
        def ranking(self, imagem):
            assert estado["abertas"] == 0  # conexão aberta durante a inferência seguraria o banco à toa
            return [("plastico", 0.9)]

    assert validar(montar(png_valido, Conferente(), conectar=conectar)).status_code == 200
    assert estado["aberturas"] == 2


def test_categoria_do_banco_fora_do_catalogo_422(png_valido, classificador_falso, banco_falso):
    banco_falso.dados = (URL_FOTO, "Eletrônicos")
    resposta = validar(montar(png_valido, classificador_falso))
    assert resposta.status_code == 422
    assert "Eletrônicos" in resposta.json()["detail"]
    assert classificador_falso.imagens == []
    assert banco_falso.gravados == []


def test_triagem_postagem_inexistente_404(monkeypatch, png_valido, classificador_falso):
    def inexistente(conexao, id_postagem):
        raise banco.PostagemNaoEncontrada(id_postagem)

    monkeypatch.setattr(banco, "dados_triagem", inexistente)
    resposta = validar(montar(png_valido, classificador_falso))
    assert resposta.status_code == 404
    assert resposta.json() == {"detail": "Postagem não encontrada."}


def test_log_traz_id_postagem(png_valido, classificador_falso, caplog):
    with caplog.at_level("INFO", logger="validador_fotos.veredito"):
        validar(montar(png_valido, classificador_falso))
    assert json.loads(caplog.records[-1].getMessage())["id_postagem"] == 42


# ---------- votos e decisão ----------

def trocar(monkeypatch, nome, retorno=None, erro=None):
    chamadas = []

    def duble(conexao, *args):
        chamadas.append(args)
        if erro:
            raise erro
        return retorno

    monkeypatch.setattr(banco, nome, duble)
    return chamadas


def test_voto_devolve_saldo(monkeypatch):
    chamadas = trocar(monkeypatch, "votar", retorno=(3, True))
    resposta = votar(cliente_de_banco())
    assert resposta.status_code == 200
    assert resposta.json() == {"saldo_confianca": 3, "pontuacao_ativa": True}
    assert chamadas == [(42, 7, "aprovar", None, None)]


def test_autovoto_403(monkeypatch):
    trocar(monkeypatch, "votar", erro=banco.AutoVoto(7))
    resposta = votar(cliente_de_banco())
    assert resposta.status_code == 403
    assert resposta.json() == {"detail": "O autor não pode votar na própria postagem."}


def test_voto_repetido_409(monkeypatch):
    trocar(monkeypatch, "votar", erro=psycopg.errors.UniqueViolation("x"))
    resposta = votar(cliente_de_banco())
    assert resposta.status_code == 409
    assert resposta.json() == {"detail": "Usuário já votou nesta postagem."}


def test_erro_do_procedure_vira_422_com_a_mensagem(monkeypatch):
    trocar(monkeypatch, "votar", erro=psycopg.errors.RaiseException("Postagem já resolvida"))
    resposta = votar(cliente_de_banco())
    assert resposta.status_code == 422
    assert resposta.json() == {"detail": "Postagem já resolvida"}


def test_motivo_inexistente_422(monkeypatch):
    trocar(monkeypatch, "votar", erro=psycopg.errors.ForeignKeyViolation("x"))
    resposta = votar(cliente_de_banco(), {"usuario_id": 7, "tipo": "denunciar", "motivo_denuncia_id": 999})
    assert resposta.status_code == 422
    assert "Referência inválida" in resposta.json()["detail"]


def test_tipo_de_voto_invalido_422(monkeypatch):
    chamadas = trocar(monkeypatch, "votar", retorno=(0, False))
    assert votar(cliente_de_banco(), {"usuario_id": 7, "tipo": "curtir"}).status_code == 422
    assert chamadas == []


def test_decisao_204(monkeypatch):
    chamadas = trocar(monkeypatch, "decidir")
    resposta = decidir(cliente_de_banco())
    assert resposta.status_code == 204
    assert resposta.content == b""
    assert chamadas == [(42, 1, True)]


def test_decisao_de_nao_sindico_403(monkeypatch):
    trocar(monkeypatch, "decidir", erro=banco.NaoESindico(1))
    resposta = decidir(cliente_de_banco())
    assert resposta.status_code == 403
    assert resposta.json() == {"detail": "Só o síndico do condomínio decide postagens em análise."}


def test_rota_antiga_sumiu():
    resposta = cliente_de_banco().post(
        "/v1/validacoes", json={"url_foto": URL_FOTO, "categoria": "Plástico"}, headers={"X-Api-Key": CHAVE}
    )
    assert resposta.status_code == 404


def test_rotas_novas_exigem_chave():
    cliente = cliente_de_banco()
    assert cliente.post("/v1/postagens/42/votos", json={"usuario_id": 7, "tipo": "aprovar"}).status_code == 401
    assert cliente.post("/v1/postagens/42/decisao", json={"usuario_id": 1, "aprovar": True}).status_code == 401


def test_id_fora_do_int4_422(monkeypatch):
    chamadas = trocar(monkeypatch, "votar", retorno=(0, False))
    assert votar(cliente_de_banco(), {"usuario_id": 2**31, "tipo": "aprovar"}).status_code == 422
    assert chamadas == []


def test_banco_fora_do_ar_503_com_retry_after(png_valido, classificador_falso):
    def conectar():
        raise psycopg.OperationalError("connection refused")

    resposta = validar(montar(png_valido, classificador_falso, conectar=conectar))
    assert resposta.status_code == 503
    assert resposta.headers["Retry-After"] == "5"
    assert resposta.json() == {"detail": "Banco de dados indisponível; tente de novo."}
