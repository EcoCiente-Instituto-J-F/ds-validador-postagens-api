package br.com.ecociente.validador.core.exception;

import br.com.ecociente.validador.core.domain.StatusTriagem;

/**
 * Falha ao buscar ou abrir a foto, com o status que ela vira na triagem:
 * PEDIDO_INVALIDO = URL recusada (422), FOTO_INDISPONIVEL = o storage não entregou (502),
 * OK = arquivo que não serve como foto (veredito "não pertinente", HTTP 200).
 */
public class FotoException extends RuntimeException {

  private final StatusTriagem status;

  public FotoException(StatusTriagem status, String mensagem) {
    super(mensagem);
    this.status = status;
  }

  public StatusTriagem getStatus() {
    return status;
  }
}
