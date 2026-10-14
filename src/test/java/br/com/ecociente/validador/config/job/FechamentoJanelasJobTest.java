package br.com.ecociente.validador.config.job;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.ecociente.validador.core.gateway.PostagemValidacaoGateway;
import br.com.ecociente.validador.core.service.PostagemValidacaoService;

class FechamentoJanelasJobTest {

  @Test
  void falhaEmUmaJanelaNaoTravaAsDemais() {
    var gateway = mock(PostagemValidacaoGateway.class);
    var service = mock(PostagemValidacaoService.class);
    when(gateway.buscarJanelasVencidas(100)).thenReturn(List.of(1, 2, 3));
    org.mockito.Mockito.doThrow(new IllegalStateException("procedure falhou")).when(service).encerrarJanela(2);

    new FechamentoJanelasJob(gateway, service, 100).executar();

    verify(service).encerrarJanela(1);
    verify(service).encerrarJanela(2);
    verify(service).encerrarJanela(3);
  }

  @Test
  void semJanelasVencidasNaoFazNada() {
    var gateway = mock(PostagemValidacaoGateway.class);
    var service = mock(PostagemValidacaoService.class);
    when(gateway.buscarJanelasVencidas(100)).thenReturn(List.of());

    new FechamentoJanelasJob(gateway, service, 100).executar();

    verify(service, never()).encerrarJanela(org.mockito.ArgumentMatchers.anyInt());
  }

  @Test
  void loteForaDoIntervaloEhRecusado() {
    var gateway = mock(PostagemValidacaoGateway.class);
    var service = mock(PostagemValidacaoService.class);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new FechamentoJanelasJob(gateway, service, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
