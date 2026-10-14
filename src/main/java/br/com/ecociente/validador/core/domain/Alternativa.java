package br.com.ecociente.validador.core.domain;

/** Uma das outras classes que o modelo considerou para a foto, com confiança de 0 a 100. */
public record Alternativa(String categoria, double confianca) {
}
