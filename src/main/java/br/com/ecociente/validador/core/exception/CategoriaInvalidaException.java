package br.com.ecociente.validador.core.exception;

/** Categoria da postagem fora do catálogo do validador: HTTP 422. */
public class CategoriaInvalidaException extends RuntimeException {

  public CategoriaInvalidaException(String categoria) {
    super("Categoria fora do catálogo do validador: " + categoria);
  }
}
