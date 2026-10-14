package br.com.ecociente.validador.core.exception;

/** O arquivo baixado não serve como foto: veredito "não pertinente" com este motivo. */
public class FotoInvalidaException extends FotoException {

  public FotoInvalidaException(String mensagem) {
    super(mensagem);
  }
}
