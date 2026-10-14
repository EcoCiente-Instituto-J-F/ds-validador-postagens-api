package br.com.ecociente.validador.core.exception;

/** O storage não entregou a foto (rede, timeout, 404...): foto_indisponivel, vale tentar de novo. */
public class FotoIndisponivelException extends FotoException {

  public FotoIndisponivelException(String mensagem) {
    super(mensagem);
  }
}
