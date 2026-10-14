package br.com.ecociente.validador.entrypoint.dto;

/** Corpo de erro: {"detail": "..."}, como o serviço antigo, que o app já trata. */
public record ErroResposta(String detail) {
}
