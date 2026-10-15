package br.com.ecociente.validador.fixtures;

import java.util.List;

import br.com.ecociente.validador.config.PropriedadesValidador;

/** Propriedades de teste: sem caminhos de modelo reais. */
public final class Propriedades {

  private Propriedades() {
  }

  public static PropriedadesValidador padrao(List<String> hosts) {
    return new PropriedadesValidador(
        "chave-de-teste-com-16-chars",
        null,
        hosts,
        0.5,
        null,
        "",
        8,
        10_000_000L,
        5,
        "modelos/clip-vit-base-patch32");
  }
}
