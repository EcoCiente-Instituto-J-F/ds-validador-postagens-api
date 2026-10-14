package br.com.ecociente.validador.core.exception;

/** Falhas ao buscar ou abrir a foto. Cada subtipo vira um status diferente na triagem. */
public abstract class FotoException extends RuntimeException {

  protected FotoException(String mensagem) {
    super(mensagem);
  }
}
