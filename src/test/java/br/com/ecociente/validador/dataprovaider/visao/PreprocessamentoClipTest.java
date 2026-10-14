package br.com.ecociente.validador.dataprovaider.visao;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

class PreprocessamentoClipTest {

  @Test
  void tensorTemFormatoCHWDe224() {
    float[] tensor = PreprocessamentoClip.paraTensor(solida(640, 480, 0xFFFFFF));

    assertThat(tensor).hasSize(3 * 224 * 224);
  }

  @Test
  void brancoNormalizadoSeguiAsMediasDoClip() {
    float[] tensor = PreprocessamentoClip.paraTensor(solida(300, 300, 0xFFFFFF));

    // (1.0 - média) / desvio, canal a canal
    assertThat(tensor[0]).isCloseTo((1f - 0.48145466f) / 0.26862954f, org.assertj.core.data.Offset.offset(1e-3f));
    assertThat(tensor[224 * 224]).isCloseTo((1f - 0.4578275f) / 0.26130258f, org.assertj.core.data.Offset.offset(1e-3f));
    assertThat(tensor[2 * 224 * 224]).isCloseTo((1f - 0.40821073f) / 0.27577711f, org.assertj.core.data.Offset.offset(1e-3f));
  }

  @Test
  void fotoRetratoEhRecortadaNoCentro() {
    // topo e base pretos, meio branco: o recorte central de uma foto alta mantém só o branco
    BufferedImage imagem = new BufferedImage(200, 400, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < 400; y++) {
      for (int x = 0; x < 200; x++) {
        imagem.setRGB(x, y, y >= 100 && y < 300 ? 0xFFFFFF : 0x000000);
      }
    }

    float[] tensor = PreprocessamentoClip.paraTensor(imagem);

    float pretoNormalizado = (0f - 0.48145466f) / 0.26862954f;
    assertThat(tensor[112 * 224 + 112]).isGreaterThan(pretoNormalizado + 1f);
  }

  private static BufferedImage solida(int largura, int altura, int rgb) {
    BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < altura; y++) {
      for (int x = 0; x < largura; x++) {
        imagem.setRGB(x, y, rgb);
      }
    }
    return imagem;
  }
}
