package br.com.ecociente.validador.entrypoint.dto.response;

import br.com.ecociente.validador.core.domain.ResultadoVoto;

public record ResultadoVotoResponse(int saldoConfianca, boolean pontuacaoAtiva) {

  public static ResultadoVotoResponse de(ResultadoVoto resultado) {
    return new ResultadoVotoResponse(resultado.saldoConfianca(), resultado.pontuacaoAtiva());
  }
}
