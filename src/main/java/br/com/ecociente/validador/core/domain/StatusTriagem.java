package br.com.ecociente.validador.core.domain;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonValue;

/** ok = veredito válido; pedido_invalido = URL recusada; foto_indisponivel = o storage não entregou a foto. */
public enum StatusTriagem {

  OK,
  PEDIDO_INVALIDO,
  FOTO_INDISPONIVEL;

  @JsonValue
  public String getValor() {
    return name().toLowerCase(Locale.ROOT);
  }
}
