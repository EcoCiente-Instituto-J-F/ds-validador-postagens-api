package br.com.ecociente.validador.agentes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.ecociente.validador.core.domain.StatusTriagem;
import br.com.ecociente.validador.core.domain.VeredictoFoto;

class ContextoTriagensTest {

  @Test
  void semTriagensDizQueNadaFoiAnalisado() {
    assertThat(new ContextoTriagens().texto()).isEqualTo("Nenhuma foto foi analisada nesta conversa.");
  }

  @Test
  void descreveVereditoEConfianca() {
    var contexto = new ContextoTriagens();
    contexto.registrar(new VeredictoFoto(StatusTriagem.OK, false, "plastico", "vidro", 87.5, null, List.of(),
        List.of(), "A foto parece conter vidro, não plástico."));

    assertThat(contexto.texto()).isEqualTo(
        "- Foto de plastico: não pertinente; o modelo viu vidro com 87.5% de confiança. A foto parece conter vidro, não plástico.");
  }

  @Test
  void guardaSoAsCincoUltimas() {
    var contexto = new ContextoTriagens();
    for (int i = 0; i < 7; i++) {
      contexto.registrar(VeredictoFoto.rejeitada("vidro", "foto " + i, StatusTriagem.PEDIDO_INVALIDO));
    }

    assertThat(contexto.texto().lines()).hasSize(5);
    assertThat(contexto.texto()).doesNotContain("foto 1").contains("foto 6");
  }
}
