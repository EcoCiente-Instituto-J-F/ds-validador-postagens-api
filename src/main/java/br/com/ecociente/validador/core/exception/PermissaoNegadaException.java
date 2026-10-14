package br.com.ecociente.validador.core.exception;

/** Regra de quem pode votar ou decidir (autor votando, quem não é síndico decidindo): HTTP 403. */
public class PermissaoNegadaException extends RuntimeException {

  public PermissaoNegadaException(String mensagem) {
    super(mensagem);
  }
}
