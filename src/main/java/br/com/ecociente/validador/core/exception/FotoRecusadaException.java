package br.com.ecociente.validador.core.exception;

import br.com.ecociente.validador.core.domain.StatusTriagem;

/**
 * A triagem não produziu veredito por falha da foto ou do pedido: a resposta HTTP depende do status
 * (pedido_invalido = 422, foto_indisponivel = 502).
 */
public class FotoRecusadaException extends RuntimeException {

  private final StatusTriagem status;

  public FotoRecusadaException(StatusTriagem status, String mensagem) {
    super(mensagem);
    this.status = status;
  }

  public StatusTriagem getStatus() {
    return status;
  }
}
