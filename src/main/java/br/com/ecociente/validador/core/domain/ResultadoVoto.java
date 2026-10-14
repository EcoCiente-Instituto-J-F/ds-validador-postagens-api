package br.com.ecociente.validador.core.domain;

/** Saldo de confiança da postagem depois do voto e se a pontuação já está ativa. */
public record ResultadoVoto(int saldoConfianca, boolean pontuacaoAtiva) {
}
