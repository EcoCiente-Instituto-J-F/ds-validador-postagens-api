package br.com.ecociente.validador.core.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CategoriasTest {

  @Test
  void normalizaAcentoMaiusculasEEspacos() {
    assertThat(Categorias.normalizar("  Plástico ")).isEqualTo("plastico");
    assertThat(Categorias.normalizar("Orgânico")).isEqualTo("organico");
    assertThat(Categorias.normalizar("Papel  Branco")).isEqualTo("papel_branco");
  }

  @Test
  void aceitaSoAsCategoriasDoCatalogo() {
    assertThat(Categorias.porSlug("vidro")).isPresent();
    assertThat(Categorias.porSlug("eletronicos")).isEmpty();
    assertThat(Categorias.porSlug("nao_residuo")).isEmpty(); // fundo: não é categoria escolhível
  }

  @Test
  void naoResiduoEhClasseDoModelo() {
    assertThat(Categorias.TODAS_AS_CLASSES).hasSize(6);
    assertThat(Categorias.TODAS_AS_CLASSES).contains(Categorias.NAO_RESIDUO);
  }
}
