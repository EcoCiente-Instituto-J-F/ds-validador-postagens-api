package br.com.ecociente.validador.dataprovider.visao;

import java.awt.image.BufferedImage;

import br.com.ecociente.validador.core.imagem.Reamostragem;

/**
 * Prepara a foto como o CLIPImageProcessor: menor lado em 224 (bicúbica), recorte central de 224x224,
 * escala 0–1 e normalização com as médias e desvios do treino do CLIP. Saída em CHW, como o modelo espera.
 */
final class PreprocessamentoClip {

  static final int LADO = 224;
  private static final float[] MEDIA = {0.48145466f, 0.4578275f, 0.40821073f};
  private static final float[] DESVIO = {0.26862954f, 0.26130258f, 0.27577711f};

  private PreprocessamentoClip() {
  }

  static float[] paraTensor(BufferedImage imagem) {
    int largura = imagem.getWidth();
    int altura = imagem.getHeight();
    int novaLargura;
    int novaAltura;
    if (largura < altura) {
      novaLargura = LADO;
      novaAltura = (int) (LADO * (double) altura / largura);
    } else if (altura < largura) {
      novaAltura = LADO;
      novaLargura = (int) (LADO * (double) largura / altura);
    } else {
      novaLargura = LADO;
      novaAltura = LADO;
    }

    int[] rgb = imagem.getRGB(0, 0, largura, altura, null, 0, largura);
    int[][] canais = new int[3][largura * altura];
    for (int i = 0; i < rgb.length; i++) {
      canais[0][i] = (rgb[i] >> 16) & 0xFF;
      canais[1][i] = (rgb[i] >> 8) & 0xFF;
      canais[2][i] = rgb[i] & 0xFF;
    }

    int inicioY = (novaAltura - LADO) / 2;
    int inicioX = (novaLargura - LADO) / 2;
    float[] tensor = new float[3 * LADO * LADO];
    for (int c = 0; c < 3; c++) {
      int[] redimensionado = Reamostragem.redimensionar(
          canais[c], largura, altura, novaLargura, novaAltura, Reamostragem.Nucleo.BICUBICA);
      for (int y = 0; y < LADO; y++) {
        for (int x = 0; x < LADO; x++) {
          int valor = redimensionado[(inicioY + y) * novaLargura + inicioX + x];
          tensor[c * LADO * LADO + y * LADO + x] = (valor / 255f - MEDIA[c]) / DESVIO[c];
        }
      }
    }
    return tensor;
  }
}
