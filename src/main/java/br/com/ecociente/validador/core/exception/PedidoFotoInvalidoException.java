package br.com.ecociente.validador.core.exception;

/** URL que o serviço se recusa a buscar (anti-SSRF, esquema errado, malformada): pedido_invalido. */
public class PedidoFotoInvalidoException extends FotoException {

  public PedidoFotoInvalidoException(String mensagem) {
    super(mensagem);
  }
}
