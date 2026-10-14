package br.com.ecociente.validador.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import br.com.ecociente.validador.core.domain.ParametrosTriagem;

class TriagemConfigTest {

  @Test
  void limiaresPorCategoriaVemDeJsonComChaveNormalizada() throws Exception {
    Map<String, Double> limiares = TriagemConfig.limiaresPorCategoria("{\"Vidro\": 0.6}");

    assertThat(limiares).containsExactly(Map.entry("vidro", 0.6));
    assertThat(new ParametrosTriagem(0.5, limiares, null).limiarPara("vidro")).isEqualTo(0.6);
    assertThat(new ParametrosTriagem(0.5, limiares, null).limiarPara("metal")).isEqualTo(0.5);
  }

  @Test
  void vazioNaoTemLimiarProprio() throws Exception {
    assertThat(TriagemConfig.limiaresPorCategoria("")).isEmpty();
  }

  @Test
  void categoriaDesconhecidaFalhaNaSubida() {
    assertThatThrownBy(() -> TriagemConfig.limiaresPorCategoria("{\"eletronicos\": 0.5}"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("categoria desconhecida");
  }

  @Test
  void limiarForaDeZeroUmFalhaNaSubida() {
    assertThatThrownBy(() -> TriagemConfig.limiaresPorCategoria("{\"vidro\": 1.5}"))
        .isInstanceOf(IllegalStateException.class);
  }
}
