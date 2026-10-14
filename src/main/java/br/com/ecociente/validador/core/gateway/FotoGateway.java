package br.com.ecociente.validador.core.gateway;

import br.com.ecociente.validador.core.domain.FotoCarregada;

/** Busca a foto no storage do app com as proteções de tamanho, host e tempo. */
public interface FotoGateway {

  FotoCarregada baixar(String url);
}
