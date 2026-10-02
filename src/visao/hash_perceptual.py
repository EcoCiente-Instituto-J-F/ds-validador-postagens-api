"""Hash perceptual (dHash) para o app achar a mesma foto reenviada em postagens diferentes."""
from PIL import Image


def dhash(imagem: Image.Image) -> str:
    """64 bits (16 hex): cada bit diz se um pixel é mais claro que o vizinho da direita numa miniatura 9x8."""
    miniatura = imagem.convert("L").resize((9, 8), Image.Resampling.LANCZOS)
    pixels = miniatura.tobytes()
    bits = 0
    for linha in range(8):
        for coluna in range(8):
            bits = (bits << 1) | (pixels[linha * 9 + coluna] > pixels[linha * 9 + coluna + 1])
    return f"{bits:016x}"


def distancia(hash_a: str, hash_b: str) -> int:
    """Bits diferentes (0–64). Até ~5 é a mesma foto; o app decide o corte ao comparar com tb_postagens."""
    return (int(hash_a, 16) ^ int(hash_b, 16)).bit_count()
