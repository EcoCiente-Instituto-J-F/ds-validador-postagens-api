package br.com.ecociente.validador.dataprovider.foto;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import br.com.ecociente.validador.config.PropriedadesValidador;
import br.com.ecociente.validador.core.domain.FotoCarregada;
import br.com.ecociente.validador.core.domain.StatusTriagem;
import br.com.ecociente.validador.core.exception.FotoException;

/** Busca a foto no storage do app: só hosts permitidos (anti-SSRF), sem redirecionar, com limite de tamanho. */
@Component
public class FotoHttpGateway {

  private final Set<String> hostsPermitidos;
  private final long tamanhoMaximo;
  private final Duration timeout;
  private final HttpClient cliente;

  public FotoHttpGateway(PropriedadesValidador propriedades) {
    this.hostsPermitidos = propriedades.hostsPermitidos().stream()
        .map(host -> host.trim().toLowerCase(Locale.ROOT))
        .filter(host -> !host.isEmpty())
        .collect(Collectors.toUnmodifiableSet());
    this.tamanhoMaximo = propriedades.tamanhoMaximoBytes();
    this.timeout = Duration.ofSeconds(propriedades.timeoutDownloadSegundos());
    this.cliente = HttpClient.newBuilder()
        .connectTimeout(timeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
  }

  public FotoCarregada baixar(String url) {
    URI destino = destinoValido(url);
    return DecodificadorFoto.decodificar(baixarBytes(destino));
  }

  private URI destinoValido(String url) {
    URI destino;
    try {
      destino = new URI(url);
    } catch (URISyntaxException | NullPointerException erro) {
      throw new FotoException(StatusTriagem.PEDIDO_INVALIDO, "URL da foto malformada.");
    }
    String esquema = destino.getScheme() == null ? "" : destino.getScheme().toLowerCase(Locale.ROOT);
    if (!esquema.equals("http") && !esquema.equals("https")) {
      throw new FotoException(StatusTriagem.PEDIDO_INVALIDO, "A URL da foto precisa ser http(s).");
    }
    String host = destino.getHost() == null ? "" : destino.getHost().toLowerCase(Locale.ROOT);
    if (!hostsPermitidos.contains(host)) { // anti-SSRF: só busca no storage do app
      throw new FotoException(StatusTriagem.PEDIDO_INVALIDO, "Host não permitido: " + (host.isEmpty() ? "(vazio)" : host) + ".");
    }
    return destino;
  }

  private byte[] baixarBytes(URI destino) {
    HttpResponse<InputStream> resposta;
    try {
      resposta = cliente.send(
          HttpRequest.newBuilder(destino).timeout(timeout).GET().build(),
          HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException erro) {
      throw new FotoException(StatusTriagem.FOTO_INDISPONIVEL, "Não foi possível baixar a foto do storage.");
    } catch (InterruptedException erro) {
      Thread.currentThread().interrupt();
      throw new FotoException(StatusTriagem.FOTO_INDISPONIVEL, "Não foi possível baixar a foto do storage.");
    }
    try (InputStream corpo = resposta.body()) {
      if (resposta.statusCode() != 200) {
        throw new FotoException(StatusTriagem.FOTO_INDISPONIVEL, "O storage respondeu HTTP " + resposta.statusCode() + ".");
      }
      return lerLimitado(corpo);
    } catch (IOException erro) {
      throw new FotoException(StatusTriagem.FOTO_INDISPONIVEL, "Não foi possível baixar a foto do storage.");
    }
  }

  private byte[] lerLimitado(InputStream corpo) throws IOException {
    ByteArrayOutputStream dados = new ByteArrayOutputStream();
    byte[] bloco = new byte[8192];
    long total = 0;
    int lidos;
    while ((lidos = corpo.read(bloco)) != -1) {
      total += lidos;
      if (total > tamanhoMaximo) {
        throw new FotoException(StatusTriagem.OK, "A foto passa do limite de " + megabytes(tamanhoMaximo) + " MB.");
      }
      dados.write(bloco, 0, lidos);
    }
    return dados.toByteArray();
  }

  /** 10000000 -> "10", como o :g do Python. */
  static String megabytes(long bytes) {
    return BigDecimal.valueOf(bytes).divide(BigDecimal.valueOf(1_000_000)).stripTrailingZeros().toPlainString();
  }
}
