package br.com.ecociente.validador.entrypoint.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Decisão do síndico sobre uma postagem em análise. */
public record DecisaoRequest(
    @NotNull @Min(1) @Max(2_147_483_647) Integer usuarioId,
    @NotNull Boolean aprovar) {
}
