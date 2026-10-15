package br.com.ecociente.validador.dataprovider.foto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import br.com.ecociente.validador.config.PropriedadesValidador;
import br.com.ecociente.validador.core.domain.StatusTriagem;
import br.com.ecociente.validador.core.exception.FotoException;
import br.com.ecociente.validador.fixtures.Propriedades;

class FotoHttpGatewayTest {

  private HttpServer servidor;
  private byte[] png;

  @BeforeEach
  void subirServidor() throws IOException {
    BufferedImage imagem = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream saida = new ByteArrayOutputStream();
    ImageIO.write(imagem, "png", saida);
    png = saida.toByteArray();

    servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    servidor.createContext("/ok.png", troca -> responder(troca, 200, png));
    servidor.createContext("/grande.png", troca -> responder(troca, 200, new byte[5_000]));
    servidor.createContext("/falta.png", troca -> responder(troca, 404, new byte[0]));
    servidor.start();
  }

  @AfterEach
  void pararServidor() {
    servidor.stop(0);
  }

  @Test
  void baixaEDecodificaFotoDeHostPermitido() {
    var gateway = new FotoHttpGateway(propriedades(1_000_000));

    var foto = gateway.baixar(url("/ok.png"));

    assertThat(foto.imagem().getWidth()).isEqualTo(40);
    assertThat(foto.imagem().getHeight()).isEqualTo(30);
  }

  @Test
  void hostForaDaListaEhRecusado() {
    var gateway = new FotoHttpGateway(Propriedades.padrao(List.of("storage.exemplo.com")));

    assertThatThrownBy(() -> gateway.baixar(url("/ok.png")))
        .isInstanceOf(FotoException.class).hasFieldOrPropertyWithValue("status", StatusTriagem.PEDIDO_INVALIDO)
        .hasMessageStartingWith("Host não permitido: 127.0.0.1");
  }

  @Test
  void esquemaNaoHttpEhRecusado() {
    var gateway = new FotoHttpGateway(propriedades(1_000_000));

    assertThatThrownBy(() -> gateway.baixar("ftp://127.0.0.1/ok.png"))
        .isInstanceOf(FotoException.class).hasFieldOrPropertyWithValue("status", StatusTriagem.PEDIDO_INVALIDO)
        .hasMessage("A URL da foto precisa ser http(s).");
  }

  @Test
  void urlMalformadaEhRecusada() {
    var gateway = new FotoHttpGateway(propriedades(1_000_000));

    assertThatThrownBy(() -> gateway.baixar("http://exemplo com espaço/x.png"))
        .isInstanceOf(FotoException.class).hasFieldOrPropertyWithValue("status", StatusTriagem.PEDIDO_INVALIDO)
        .hasMessage("URL da foto malformada.");
  }

  @Test
  void storageRespondendoErroVirFotoIndisponivel() {
    var gateway = new FotoHttpGateway(propriedades(1_000_000));

    assertThatThrownBy(() -> gateway.baixar(url("/falta.png")))
        .isInstanceOf(FotoException.class).hasFieldOrPropertyWithValue("status", StatusTriagem.FOTO_INDISPONIVEL)
        .hasMessage("O storage respondeu HTTP 404.");
  }

  @Test
  void fotoAcimaDoLimiteEhInvalida() {
    var gateway = new FotoHttpGateway(propriedades(1_000));

    assertThatThrownBy(() -> gateway.baixar(url("/grande.png")))
        .isInstanceOf(FotoException.class).hasFieldOrPropertyWithValue("status", StatusTriagem.OK)
        .hasMessage("A foto passa do limite de 0.001 MB.");
  }

  @Test
  void megabytesSemZerosAFrente() {
    assertThat(FotoHttpGateway.megabytes(10_000_000)).isEqualTo("10");
    assertThat(FotoHttpGateway.megabytes(1_500_000)).isEqualTo("1.5");
  }

  private PropriedadesValidador propriedades(long tamanhoMaximo) {
    PropriedadesValidador base = Propriedades.padrao(List.of("127.0.0.1"));
    return new PropriedadesValidador(base.chaveApi(), null, base.hostsPermitidos(), 0.5, null, "", 8,
        tamanhoMaximo, 5, base.clipDir());
  }

  private String url(String caminho) {
    return "http://127.0.0.1:" + servidor.getAddress().getPort() + caminho;
  }

  private static void responder(com.sun.net.httpserver.HttpExchange troca, int status, byte[] corpo) throws IOException {
    troca.sendResponseHeaders(status, corpo.length == 0 ? -1 : corpo.length);
    if (corpo.length > 0) {
      troca.getResponseBody().write(corpo);
    }
    troca.close();
  }
}
