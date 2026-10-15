package br.com.ecociente.validador.dataprovider.foto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import br.com.ecociente.validador.core.exception.FotoInvalidaException;

class DecodificadorFotoTest {

  @Test
  void pngDecodificaEmRgbSemExif() throws IOException {
    var foto = DecodificadorFoto.decodificar(png(20, 10));

    assertThat(foto.imagem().getWidth()).isEqualTo(20);
    assertThat(foto.imagem().getType()).isEqualTo(BufferedImage.TYPE_INT_RGB);
    assertThat(foto.semExif()).isTrue(); // PNG gerado pelo ImageIO não tem EXIF
  }

  @Test
  void bytesQueNaoSaoImagemViramFotoInvalida() {
    assertThatThrownBy(() -> DecodificadorFoto.decodificar("isto não é uma imagem".getBytes()))
        .isInstanceOf(FotoInvalidaException.class)
        .hasMessage("O arquivo não é uma imagem válida.");
  }

  @Test
  void orientacaoSemExifNaoGira() throws IOException {
    BufferedImage imagem = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);

    assertThat(DecodificadorFoto.aplicarOrientacao(png(20, 10), imagem)).isSameAs(imagem);
  }

  private static byte[] png(int largura, int altura) throws IOException {
    ByteArrayOutputStream saida = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(largura, altura, BufferedImage.TYPE_INT_RGB), "png", saida);
    return saida.toByteArray();
  }
}
