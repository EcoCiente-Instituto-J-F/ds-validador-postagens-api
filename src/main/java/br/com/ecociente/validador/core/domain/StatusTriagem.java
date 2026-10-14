package br.com.ecociente.validador.core.domain;

/** ok = veredito válido; pedido_invalido = URL recusada; foto_indisponivel = o storage não entregou a foto. */
public enum StatusTriagem {

  OK("ok"),
  PEDIDO_INVALIDO("pedido_invalido"),
  FOTO_INDISPONIVEL("foto_indisponivel");

  private final String valor;

  StatusTriagem(String valor) {
    this.valor = valor;
  }

  public String getValor() {
    return valor;
  }
}
