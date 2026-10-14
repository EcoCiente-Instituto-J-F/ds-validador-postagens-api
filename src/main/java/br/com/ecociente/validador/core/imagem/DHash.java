package br.com.ecociente.validador.core.imagem;

import java.awt.image.BufferedImage;

/**
 * Hash perceptual (dHash) para o app achar a mesma foto reenviada em postagens diferentes.
 * Mudar este algoritmo invalida o hash_foto já gravado: qualquer ajuste exige recalcular o banco.
 */
public final class DHash {

  private DHash() {
  }

  /** 64 bits (16 hex): cada bit diz se um pixel é mais claro que o vizinho da direita numa miniatura 9x8. */
  public static String calcular(BufferedImage imagem) {
    int largura = imagem.getWidth();
    int altura = imagem.getHeight();
    int[] cinza = Luminancia.canalCinza(imagem);
    int[] miniatura = Reamostragem.redimensionar(cinza, largura, altura, 9, 8, Reamostragem.Nucleo.LANCZOS);
    long bits = 0;
    for (int linha = 0; linha < 8; linha++) {
      for (int coluna = 0; coluna < 8; coluna++) {
        int atual = miniatura[linha * 9 + coluna];
        int vizinho = miniatura[linha * 9 + coluna + 1];
        bits = (bits << 1) | (atual > vizinho ? 1 : 0);
      }
    }
    return String.format("%016x", bits);
  }

  /** Bits diferentes (0–64). Até ~5 é a mesma foto; o app decide o corte ao comparar com tb_postagens. */
  public static int distancia(String hashA, String hashB) {
    return Long.bitCount(Long.parseUnsignedLong(hashA, 16) ^ Long.parseUnsignedLong(hashB, 16));
  }
}
