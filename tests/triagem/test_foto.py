import io

import httpx
import pytest
from PIL import Image

from src.visao import foto
from src.visao.foto import FotoIndisponivel, FotoInvalida, PedidoInvalido, abrir_imagem, baixar_foto

HOSTS = frozenset({"storage.exemplo.com"})
URL = "https://storage.exemplo.com/postagens/42.jpg"


def storage_falso(resposta: httpx.Response | Exception, **opcoes) -> httpx.Client:
    """Cliente httpx que, em vez de ir à rede, devolve `resposta` (ou levanta a exceção)."""

    def responder(request: httpx.Request) -> httpx.Response:
        if isinstance(resposta, Exception):
            raise resposta
        return resposta

    return httpx.Client(transport=httpx.MockTransport(responder), **opcoes)


def baixar(url: str = URL, cliente: httpx.Client | None = None, tamanho_maximo: int = 1024) -> bytes:
    cliente = cliente or storage_falso(httpx.Response(200, content=b"bytes-da-foto"))
    return baixar_foto(url, hosts_permitidos=HOSTS, tamanho_maximo=tamanho_maximo, cliente=cliente)


# ---------- baixar_foto ----------

def test_baixa_foto_de_host_permitido():
    assert baixar() == b"bytes-da-foto"


def test_url_assinada_chega_intacta_ao_storage():
    recebidas = []

    def responder(request: httpx.Request) -> httpx.Response:
        recebidas.append(str(request.url))
        return httpx.Response(200, content=b"ok")

    url = "https://storage.exemplo.com/v0/b/app/o/postagens%2F42.jpg?alt=media&token=abc-123"
    baixar(url, cliente=httpx.Client(transport=httpx.MockTransport(responder)))
    assert recebidas == [url]


@pytest.mark.parametrize("url", ["ftp://storage.exemplo.com/42.jpg", "file:///etc/passwd", "nao-e-url"])
def test_recusa_esquema_que_nao_e_http(url):
    with pytest.raises(PedidoInvalido):
        baixar(url)


@pytest.mark.parametrize(
    "url",
    [
        "https://169.254.169.254/latest/meta-data/",  # metadados da AWS: SSRF clássico
        "http://localhost:8000/saude",
        "https://storage.exemplo.com@evil.com/42.jpg",  # truque de userinfo
    ],
)
def test_recusa_host_fora_da_lista(url):
    with pytest.raises(PedidoInvalido, match="Host não permitido"):
        baixar(url)


def test_foto_maior_que_o_limite_e_invalida():
    cliente = storage_falso(httpx.Response(200, content=b"x" * 2048))
    with pytest.raises(FotoInvalida, match="limite"):
        baixar(cliente=cliente, tamanho_maximo=1024)


def test_status_diferente_de_200_vira_foto_indisponivel():
    with pytest.raises(FotoIndisponivel, match="404"):
        baixar(cliente=storage_falso(httpx.Response(404)))


def test_nao_segue_redirect_mesmo_com_cliente_que_seguiria():
    redirect = httpx.Response(302, headers={"Location": "http://10.0.0.1/interno"})
    with pytest.raises(FotoIndisponivel, match="302"):
        baixar(cliente=storage_falso(redirect, follow_redirects=True))


def test_timeout_do_storage_vira_foto_indisponivel():
    with pytest.raises(FotoIndisponivel):
        baixar(cliente=storage_falso(httpx.ReadTimeout("lento demais")))


# ---------- abrir_imagem ----------

def test_abre_png_valido_como_rgb(png_valido):
    imagem = abrir_imagem(png_valido)
    assert imagem.mode == "RGB"
    assert imagem.size == (8, 8)


def test_png_com_transparencia_vira_rgb():
    buffer = io.BytesIO()
    Image.new("RGBA", (4, 4), (0, 0, 255, 128)).save(buffer, format="PNG")
    assert abrir_imagem(buffer.getvalue()).mode == "RGB"


def test_retrato_salvo_deitado_com_exif_fica_em_pe():
    exif = Image.Exif()
    exif[0x0112] = 6  # Orientation = girar 90°: o que o celular grava numa foto em pé
    buffer = io.BytesIO()
    Image.new("RGB", (40, 20), "green").save(buffer, format="JPEG", exif=exif)
    assert abrir_imagem(buffer.getvalue()).size == (20, 40)


def test_aceita_heic_do_iphone():
    buffer = io.BytesIO()
    Image.new("RGB", (40, 20), "blue").save(buffer, format="HEIF")
    imagem = abrir_imagem(buffer.getvalue())
    assert (imagem.mode, imagem.size) == ("RGB", (40, 20))


def test_resolucao_acima_do_limite_e_recusada_antes_de_decodificar(png_valido, monkeypatch):
    monkeypatch.setattr(foto, "LIMITE_PIXELS", 10)  # o PNG de teste tem 8x8 = 64 px
    with pytest.raises(FotoInvalida, match="resolução"):
        abrir_imagem(png_valido)


def test_bytes_que_nao_sao_imagem_viram_foto_invalida():
    with pytest.raises(FotoInvalida, match="imagem válida"):
        abrir_imagem(b"<html>Access Denied</html>")


def test_imagem_truncada_vira_foto_invalida(png_valido):
    with pytest.raises(FotoInvalida):
        abrir_imagem(png_valido[: len(png_valido) // 2])
