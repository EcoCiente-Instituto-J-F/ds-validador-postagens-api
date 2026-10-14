package br.com.ecociente.validador.core.exception;

/** Postagem inexistente: HTTP 404. */
public class NotFoundException extends RuntimeException {

  public NotFoundException(String mensagem) {
    super(mensagem);
  }
}
