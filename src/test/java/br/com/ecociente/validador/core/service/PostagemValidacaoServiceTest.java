package br.com.ecociente.validador.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import br.com.ecociente.validador.core.domain.ResultadoVoto;
import br.com.ecociente.validador.core.domain.VinculoTrust;
import br.com.ecociente.validador.core.exception.NotFoundException;
import br.com.ecociente.validador.core.exception.PermissaoNegadaException;
import br.com.ecociente.validador.core.gateway.PostagemValidacaoGateway;

class PostagemValidacaoServiceTest {

  private PostagemValidacaoGateway gateway;
  private PostagemValidacaoService service;

  @BeforeEach
  void configurar() {
    gateway = mock(PostagemValidacaoGateway.class);
    service = new PostagemValidacaoService(gateway);
  }

  @Test
  void autorNaoVotaNaPropriaPostagem() {
    when(gateway.buscarUsuarioAutor(10)).thenReturn(Optional.of(7));

    assertThatThrownBy(() -> service.votar(10, 7, "aprovar", null, null))
        .isInstanceOf(PermissaoNegadaException.class)
        .hasMessage("O autor não pode votar na própria postagem.");
    verify(gateway, never()).registrarVoto(anyInt(), anyInt(), anyString(), any(), any());
  }

  @Test
  void votoDeOutroUsuarioVaiParaOGateway() {
    when(gateway.buscarUsuarioAutor(10)).thenReturn(Optional.of(7));
    when(gateway.registrarVoto(10, 8, "aprovar", null, null)).thenReturn(new ResultadoVoto(1, true));

    assertThat(service.votar(10, 8, "aprovar", null, null)).isEqualTo(new ResultadoVoto(1, true));
  }

  @Test
  void votoEmPostagemInexistenteEhNotFound() {
    when(gateway.buscarUsuarioAutor(99)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.votar(99, 8, "aprovar", null, null))
        .isInstanceOf(NotFoundException.class)
        .hasMessage("Postagem não encontrada.");
  }

  @Test
  void soSindicoDecideEAtualizaTrustDosEnvolvidos() {
    when(gateway.existePostagem(10)).thenReturn(true);
    when(gateway.buscarUsuarioSindico(10)).thenReturn(1);
    when(gateway.buscarEnvolvidosTrust(10)).thenReturn(List.of(new VinculoTrust(3, 7), new VinculoTrust(3, 8)));

    service.decidir(10, 1, true);

    verify(gateway).registrarDecisao(10, true);
    verify(gateway).atualizarTrustScore(7, 3);
    verify(gateway).atualizarTrustScore(8, 3);
  }

  @Test
  void outroUsuarioNaoDecide() {
    when(gateway.existePostagem(10)).thenReturn(true);
    when(gateway.buscarUsuarioSindico(10)).thenReturn(1);

    assertThatThrownBy(() -> service.decidir(10, 2, true))
        .isInstanceOf(PermissaoNegadaException.class)
        .hasMessage("Só o síndico do condomínio decide postagens em análise.");
    verify(gateway, never()).registrarDecisao(anyInt(), org.mockito.ArgumentMatchers.anyBoolean());
  }

  @Test
  void condominioSemSindicoRecusaDecisao() {
    when(gateway.existePostagem(10)).thenReturn(true);
    when(gateway.buscarUsuarioSindico(10)).thenReturn(null);

    assertThatThrownBy(() -> service.decidir(10, 1, true)).isInstanceOf(PermissaoNegadaException.class);
  }

  @Test
  void encerrarJanelaAtualizaTrust() {
    when(gateway.buscarEnvolvidosTrust(10)).thenReturn(List.of(new VinculoTrust(3, 7)));

    service.encerrarJanela(10);

    verify(gateway).encerrarJanela(10);
    verify(gateway).atualizarTrustScore(7, 3);
  }
}
