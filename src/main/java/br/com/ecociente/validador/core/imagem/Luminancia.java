package br.com.ecociente.validador.core.imagem;

import java.awt.image.BufferedImage;

/** Tons de cinza no mesmo cálculo do Pillow (RGB -> L), para os hashes e a análise de frequência baterem. */
public final class Luminancia {

  private Luminancia() {
  }

  /** Luminância de um pixel RGB (inteiros 0–255). */
  public static int de(int r, int g, int b) {
    return (r * 19595 + g * 38470 + b * 7471 + 0x8000) >> 16;
  }

  /** Canal de cinza da imagem inteira, linha a linha. */
  public static int[] canalCinza(BufferedImage imagem) {
    return canalCinza(imagem, 0, 0, imagem.getWidth(), imagem.getHeight());
  }

  /** Canal de cinza de um recorte retangular, linha a linha. */
  public static int[] canalCinza(BufferedImage imagem, int x0, int y0, int largura, int altura) {
    int[] rgb = imagem.getRGB(x0, y0, largura, altura, null, 0, largura);
    int[] cinza = new int[largura * altura];
    for (int i = 0; i < rgb.length; i++) {
      int p = rgb[i];
      cinza[i] = de((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
    }
    return cinza;
  }
}
