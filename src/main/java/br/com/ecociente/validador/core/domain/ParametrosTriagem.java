package br.com.ecociente.validador.core.domain;

import java.util.Map;

/**
 * Limiares da triagem. limiarRevisao nulo = sem zona cinza: a foto ou passa ou não passa.
 */
public record ParametrosTriagem(double limiarPadrao, Map<String, Double> limiaresPorCategoria, Double limiarRevisao) {

  public double limiarPara(String categoria) {
    return limiaresPorCategoria.getOrDefault(categoria, limiarPadrao);
  }
}
