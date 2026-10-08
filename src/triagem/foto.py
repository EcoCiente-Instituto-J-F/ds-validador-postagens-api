"""Busca a foto no storage com segurança e a entrega pronta (RGB, em pé) para o modelo."""
import io

import httpx
from PIL import Image, ImageOps
from pillow_heif import register_heif_opener

register_heif_opener()  # aceita HEIC/HEIF, o formato padrão das fotos de iPhone

EXTENSOES = {".jpg", ".jpeg", ".png", ".webp", ".heic"}  # o que a calibração e o treino varrem nas pastas de amostras
LIMITE_PIXELS = 50_000_000  # ~50 MP: cobre câmera de celular e barra "bomba de descompressão"


class PedidoInvalido(Exception):
    """URL que o serviço se recusa a buscar -> HTTP 422 (erro de integração de quem chamou)."""


class FotoInvalida(Exception):
    """O arquivo baixado não serve como foto -> veredito "não pertinente" com este motivo."""


class FotoIndisponivel(Exception):
    """O storage não entregou a foto (rede, timeout, 404...) -> HTTP 502; vale tentar de novo."""


def baixar_foto(url: str, *, hosts_permitidos: frozenset[str], tamanho_maximo: int, cliente: httpx.Client) -> bytes:
    try:
        destino = httpx.URL(url)
    except httpx.InvalidURL as erro:
        raise PedidoInvalido("URL da foto malformada.") from erro
    if destino.scheme not in ("http", "https"):
        raise PedidoInvalido("A URL da foto precisa ser http(s).")
    if destino.host not in hosts_permitidos:  # anti-SSRF: só busca no storage do app
        raise PedidoInvalido(f"Host não permitido: {destino.host or '(vazio)'}.")
    try:
        with cliente.stream("GET", destino, follow_redirects=False) as resposta:
            if resposta.status_code != 200:
                raise FotoIndisponivel(f"O storage respondeu HTTP {resposta.status_code}.")
            dados = bytearray()
            for bloco in resposta.iter_bytes():
                dados += bloco
                if len(dados) > tamanho_maximo:
                    raise FotoInvalida(f"A foto passa do limite de {tamanho_maximo / 1_000_000:g} MB.")
    except httpx.HTTPError as erro:
        raise FotoIndisponivel("Não foi possível baixar a foto do storage.") from erro
    return bytes(dados)


def abrir_imagem(dados: bytes) -> Image.Image:
    try:
        imagem = Image.open(io.BytesIO(dados))  # só lê o cabeçalho; ainda não decodificou
        if imagem.width * imagem.height > LIMITE_PIXELS:
            raise FotoInvalida("A foto tem resolução grande demais.")
        imagem.draft("RGB", (1024, 1024))  # JPEG: já decodifica reduzida (menos RAM e CPU)
        imagem = ImageOps.exif_transpose(imagem)  # celular salva retrato "deitado" + tag EXIF
        return imagem.convert("RGB")
    except FotoInvalida:
        raise
    except Exception as erro:  # o Pillow lança tipos variados para arquivo corrompido ou desconhecido
        raise FotoInvalida("O arquivo não é uma imagem válida.") from erro
