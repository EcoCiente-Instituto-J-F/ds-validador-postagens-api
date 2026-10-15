package br.com.ecociente.validador.entrypoint.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Voto de um morador: "aprovar" ou "denunciar" (denúncia exige motivo_denuncia_id). */
public record VotoRequest(
    @NotNull @Min(1) Integer usuarioId,
    @NotNull @Pattern(regexp = "aprovar|denunciar") String tipo,
    @Min(1) Integer motivoDenunciaId,
    @Size(max = 255) String comentario) {
}
