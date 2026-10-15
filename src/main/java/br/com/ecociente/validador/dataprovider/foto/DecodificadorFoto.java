package br.com.ecociente.validador.dataprovider.foto;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;

import br.com.ecociente.validador.core.domain.FotoCarregada;
import br.com.ecociente.validador.core.domain.StatusTriagem;
import br.com.ecociente.validador.core.exception.FotoException;

/** Abre o arquivo baixado como imagem RGB em pé (com a orientação EXIF aplicada), sem depender do Pillow. */
public final class DecodificadorFoto {

  static final long LIMITE_PIXELS = 50_000_000L; // ~50 MP: cobre câmera de celular e barra "bomba de descompressão"
  static final String MSG_INVALIDA = "O arquivo não é uma imagem válida.";

  private DecodificadorFoto() {
  }

  public static FotoCarregada decodificar(byte[] dados) {
    Metadata metadados = metadados(dados);
    BufferedImage imagem = aplicarOrientacao(orientacaoExif(metadados), abrir(dados));
    return new FotoCarregada(paraRgb(imagem), semExif(metadados));
  }

  static BufferedImage abrir(byte[] dados) {
    try (ImageInputStream entrada = ImageIO.createImageInputStream(new ByteArrayInputStream(dados))) {
      if (entrada == null) {
        throw new FotoException(StatusTriagem.OK, MSG_INVALIDA);
      }
      Iterator<ImageReader> leitores = ImageIO.getImageReaders(entrada);
      if (!leitores.hasNext()) {
        throw new FotoException(StatusTriagem.OK, MSG_INVALIDA);
      }
      ImageReader leitor = leitores.next();
      try {
        leitor.setInput(entrada);
        // lê só o cabeçalho para barrar imagem enorme antes de ocupar a memória
        if ((long) leitor.getWidth(0) * leitor.getHeight(0) > LIMITE_PIXELS) {
          throw new FotoException(StatusTriagem.OK, "A foto tem resolução grande demais.");
        }
        BufferedImage imagem = leitor.read(0);
        if (imagem == null) {
          throw new FotoException(StatusTriagem.OK, MSG_INVALIDA);
        }
        return imagem;
      } finally {
        leitor.dispose();
      }
    } catch (FotoException erro) {
      throw erro;
    } catch (IOException | RuntimeException erro) { // o ImageIO lança tipos variados para arquivo corrompido
      throw new FotoException(StatusTriagem.OK, MSG_INVALIDA);
    }
  }

  /** Celular salva retrato "deitado" + tag EXIF: gira a imagem como o Pillow faz no exif_transpose. */
  static BufferedImage aplicarOrientacao(int orientacao, BufferedImage imagem) {
    if (orientacao == 1) {
      return imagem;
    }
    int largura = imagem.getWidth();
    int altura = imagem.getHeight();
    boolean trocaEixos = orientacao >= 5;
    int novaLargura = trocaEixos ? altura : largura;
    int novaAltura = trocaEixos ? largura : altura;
    AffineTransform transformacao = transformacao(orientacao, largura, altura);
    BufferedImage girada = new BufferedImage(novaLargura, novaAltura, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = girada.createGraphics();
    g.drawImage(imagem, transformacao, null);
    g.dispose();
    return girada;
  }

  /** Matriz que leva cada pixel da origem para a posição na imagem já orientada. */
  private static AffineTransform transformacao(int orientacao, int w, int h) {
    return switch (orientacao) {
      case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0);
      case 3 -> new AffineTransform(-1, 0, 0, -1, w, h);
      case 4 -> new AffineTransform(1, 0, 0, -1, 0, h);
      case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
      case 6 -> new AffineTransform(0, 1, -1, 0, h, 0);
      case 7 -> new AffineTransform(0, -1, -1, 0, h, w);
      case 8 -> new AffineTransform(0, -1, 1, 0, 0, w);
      default -> new AffineTransform();
    };
  }

  static BufferedImage paraRgb(BufferedImage imagem) {
    if (imagem.getType() == BufferedImage.TYPE_INT_RGB) {
      return imagem;
    }
    BufferedImage rgb = new BufferedImage(imagem.getWidth(), imagem.getHeight(), BufferedImage.TYPE_INT_RGB);
    Graphics2D g = rgb.createGraphics();
    g.drawImage(imagem, 0, 0, null);
    g.dispose();
    return rgb;
  }

  /** Metadados do arquivo, ou nulo se ilegíveis: não impedem a triagem (a foto segue sem rotação). */
  static Metadata metadados(byte[] dados) {
    try {
      return ImageMetadataReader.readMetadata(new ByteArrayInputStream(dados));
    } catch (Exception erro) {
      return null;
    }
  }

  static int orientacaoExif(Metadata metadados) {
    ExifIFD0Directory exif = metadados == null ? null : metadados.getFirstDirectoryOfType(ExifIFD0Directory.class);
    if (exif == null || !exif.containsTag(ExifIFD0Directory.TAG_ORIENTATION)) {
      return 1;
    }
    Integer orientacao = exif.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
    return orientacao == null ? 1 : orientacao;
  }

  /** Sem nenhum EXIF: indício fraco (print, download e foto reenviada por apps costumam não trazer). */
  static boolean semExif(Metadata metadados) {
    return metadados != null
        && metadados.getDirectoriesOfType(ExifIFD0Directory.class).stream().noneMatch(d -> d.getTagCount() > 0);
  }
}
