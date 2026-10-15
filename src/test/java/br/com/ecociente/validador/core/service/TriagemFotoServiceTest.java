package br.com.ecociente.validador.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.ecociente.validador.core.domain.ClasseProbabilidade;
import br.com.ecociente.validador.core.domain.FotoCarregada;
import br.com.ecociente.validador.core.domain.ParametrosTriagem;
import br.com.ecociente.validador.core.domain.StatusTriagem;
import br.com.ecociente.validador.core.exception.FotoException;
import br.com.ecociente.validador.dataprovider.foto.FotoHttpGateway;
import br.com.ecociente.validador.dataprovider.visao.ClassificadorClipGateway;

class TriagemFotoServiceTest {

  private final FotoHttpGateway fotos = mock(FotoHttpGateway.class);
  private final ClassificadorClipGateway classificador = mock(ClassificadorClipGateway.class);
  private final ParametrosTriagem parametros = new ParametrosTriagem(0.5, java.util.Map.of(), null);
  private final BufferedImage imagem = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);

  private TriagemFotoService servico() {
    return new TriagemFotoService(fotos, classificador, parametros);
  }

  @Test
  void urlRecusadaViraPedidoInvalido() {
    when(fotos.baixar(anyString()))
        .thenThrow(new FotoException(StatusTriagem.PEDIDO_INVALIDO, "Host não permitido: x."));

    var veredito = servico().analisar("https://x/y.jpg", "vidro");

    assertThat(veredito.status()).isEqualTo(StatusTriagem.PEDIDO_INVALIDO);
    assertThat(veredito.pertinente()).isNull();
    assertThat(veredito.mensagem()).isEqualTo("Host não permitido: x.");
  }

  @Test
  void storageFora_viraFotoIndisponivel() {
    when(fotos.baixar(anyString()))
        .thenThrow(new FotoException(StatusTriagem.FOTO_INDISPONIVEL, "O storage respondeu HTTP 404."));

    assertThat(servico().analisar("https://x/y.jpg", "vidro").status()).isEqualTo(StatusTriagem.FOTO_INDISPONIVEL);
  }

  @Test
  void arquivoNaoImagemViraNaoPertinenteComStatusOk() {
    when(fotos.baixar(anyString()))
        .thenThrow(new FotoException(StatusTriagem.OK, "O arquivo não é uma imagem válida."));

    var veredito = servico().analisar("https://x/y.jpg", "vidro");

    assertThat(veredito.status()).isEqualTo(StatusTriagem.OK);
    assertThat(veredito.pertinente()).isFalse();
    assertThat(veredito.confianca()).isNull();
  }

  @Test
  void fotoAnalisadaTragHashESinais() {
    when(fotos.baixar(anyString())).thenReturn(new FotoCarregada(imagem, true));
    when(classificador.ranking(any())).thenReturn(List.of(new ClasseProbabilidade("vidro", 0.9)));

    var veredito = servico().analisar("https://x/y.jpg", "vidro");

    assertThat(veredito.pertinente()).isTrue();
    assertThat(veredito.hashFoto()).hasSize(16);
    assertThat(veredito.sinais()).containsExactly("sem_exif");
  }

  @Test
  void zonaDeRevisaoFicaSemVeredito() {
    when(fotos.baixar(anyString())).thenReturn(new FotoCarregada(imagem, false));
    when(classificador.ranking(any())).thenReturn(List.of(new ClasseProbabilidade("vidro", 0.3)));
    ParametrosTriagem comRevisao = new ParametrosTriagem(0.5, java.util.Map.of(), 0.2);

    var veredito = new TriagemFotoService(fotos, classificador, comRevisao).analisar("https://x/y.jpg", "vidro");

    assertThat(veredito.pertinente()).isNull();
    assertThat(veredito.sinais()).isEmpty();
  }
}
