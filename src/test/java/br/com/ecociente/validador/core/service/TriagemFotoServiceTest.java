package br.com.ecociente.validador.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import br.com.ecociente.validador.core.domain.ClasseProbabilidade;
import br.com.ecociente.validador.core.domain.FotoCarregada;
import br.com.ecociente.validador.core.domain.ParametrosTriagem;
import br.com.ecociente.validador.core.domain.StatusTriagem;
import br.com.ecociente.validador.core.exception.FotoIndisponivelException;
import br.com.ecociente.validador.core.exception.FotoInvalidaException;
import br.com.ecociente.validador.core.exception.PedidoFotoInvalidoException;
import br.com.ecociente.validador.core.gateway.ClassificadorGateway;
import br.com.ecociente.validador.core.gateway.FotoGateway;
import br.com.ecociente.validador.core.gateway.SegundaOpiniaoGateway;

class TriagemFotoServiceTest {

  private final FotoGateway fotos = mock(FotoGateway.class);
  private final ClassificadorGateway classificador = mock(ClassificadorGateway.class);
  private final SegundaOpiniaoGateway opiniao = mock(SegundaOpiniaoGateway.class);
  private final ObjectProvider<SegundaOpiniaoGateway> provedorOpiniao = mock(ObjectProvider.class);
  private final ParametrosTriagem parametros = new ParametrosTriagem(0.5, java.util.Map.of(), null);
  private final BufferedImage imagem = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);

  private TriagemFotoService servico() {
    when(provedorOpiniao.getIfAvailable()).thenReturn(null);
    return new TriagemFotoService(fotos, classificador, provedorOpiniao, parametros);
  }

  @Test
  void urlRecusadaViraPedidoInvalido() {
    when(fotos.baixar(anyString())).thenThrow(new PedidoFotoInvalidoException("Host não permitido: x."));

    var veredito = servico().analisar("https://x/y.jpg", "vidro");

    assertThat(veredito.status()).isEqualTo(StatusTriagem.PEDIDO_INVALIDO);
    assertThat(veredito.pertinente()).isNull();
    assertThat(veredito.mensagem()).isEqualTo("Host não permitido: x.");
  }

  @Test
  void storageFora_viraFotoIndisponivel() {
    when(fotos.baixar(anyString())).thenThrow(new FotoIndisponivelException("O storage respondeu HTTP 404."));

    assertThat(servico().analisar("https://x/y.jpg", "vidro").status()).isEqualTo(StatusTriagem.FOTO_INDISPONIVEL);
  }

  @Test
  void arquivoNaoImagemViraNaoPertinenteComStatusOk() {
    when(fotos.baixar(anyString())).thenThrow(new FotoInvalidaException("O arquivo não é uma imagem válida."));

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
    assertThat(veredito.sinais()).contains("sem_exif");
  }

  @Test
  void zonaDeRevisaoConsultaSegundaOpiniao() {
    when(fotos.baixar(anyString())).thenReturn(new FotoCarregada(imagem, false));
    when(classificador.ranking(any())).thenReturn(List.of(new ClasseProbabilidade("vidro", 0.3)));
    ParametrosTriagem comRevisao = new ParametrosTriagem(0.5, java.util.Map.of(), 0.2);
    when(provedorOpiniao.getIfAvailable()).thenReturn(opiniao);
    when(opiniao.avaliar(any(), anyString())).thenReturn(Optional.of(true));

    var veredito = new TriagemFotoService(fotos, classificador, provedorOpiniao, comRevisao)
        .analisar("https://x/y.jpg", "vidro");

    assertThat(veredito.pertinente()).isTrue();
    assertThat(veredito.segundaOpiniao()).isTrue();
    assertThat(veredito.mensagem()).contains("segunda análise");
  }

  @Test
  void semRevisaoNaoChamaSegundaOpiniao() {
    when(fotos.baixar(anyString())).thenReturn(new FotoCarregada(imagem, false));
    when(classificador.ranking(any())).thenReturn(List.of(new ClasseProbabilidade("vidro", 0.9)));
    when(provedorOpiniao.getIfAvailable()).thenReturn(opiniao);

    new TriagemFotoService(fotos, classificador, provedorOpiniao, parametros).analisar("https://x/y.jpg", "vidro");

    verify(opiniao, never()).avaliar(any(), anyString());
  }
}
