package br.com.ecociente.validador.core.exception;

/** Triagens em andamento no limite: HTTP 503 com Retry-After (backpressure, a fila não cresce sem fim). */
public class ServicoOcupadoException extends RuntimeException {

  public ServicoOcupadoException() {
    super("Serviço ocupado; tente de novo.");
  }
}
