package br.com.ecociente.validador.core.imagem;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

class DHashTest {

  @Test
  void hashTemDezesseisHexadecimais() {
    String hash = DHash.calcular(gradiente(64, 48));

    assertThat(hash).hasSize(16).matches("[0-9a-f]{16}");
  }

  @Test
  void mesmaImagemMesmoHash() {
    assertThat(DHash.calcular(gradiente(64, 48))).isEqualTo(DHash.calcular(gradiente(64, 48)));
  }

  /**
   * Valores gerados pelo dHash do serviço Python (Pillow, convert L + resize 9x8 LANCZOS) para as mesmas imagens.
   * Se este teste falhar, o hash_foto gravado no banco deixa de bater com o das postagens antigas.
   */
  @Test
  void bateComOHashDoPillow() {
    assertThat(DHash.calcular(gradiente(64, 48))).isEqualTo("0000000000000103");
    assertThat(DHash.calcular(gradiente(400, 300))).isEqualTo("5aaa5555aaaa554a");
    assertThat(DHash.calcular(gradiente(1000, 37))).isEqualTo("4949494812929292");
  }

  @Test
  void imagemLisaTemTodosBitsZero() {
    // sem vizinhos diferentes, nenhum pixel é "mais claro" que o da direita
    assertThat(DHash.calcular(solida(40, 40, 0x808080))).isEqualTo("0000000000000000");
  }

  @Test
  void distanciaContaBitsDiferentes() {
    assertThat(DHash.distancia("0000000000000000", "0000000000000000")).isZero();
    assertThat(DHash.distancia("0000000000000000", "ffffffffffffffff")).isEqualTo(64);
    assertThat(DHash.distancia("0000000000000001", "0000000000000003")).isEqualTo(1);
  }

  static BufferedImage gradiente(int largura, int altura) {
    BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < altura; y++) {
      for (int x = 0; x < largura; x++) {
        int v = (x * 3 + y * 2) % 256;
        imagem.setRGB(x, y, (v << 16) | (v << 8) | (255 - v));
      }
    }
    return imagem;
  }

  static BufferedImage solida(int largura, int altura, int rgb) {
    BufferedImage imagem = new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB);
    for (int y = 0; y < altura; y++) {
      for (int x = 0; x < largura; x++) {
        imagem.setRGB(x, y, rgb);
      }
    }
    return imagem;
  }
}
