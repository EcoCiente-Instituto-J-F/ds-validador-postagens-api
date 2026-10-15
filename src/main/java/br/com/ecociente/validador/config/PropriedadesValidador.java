package br.com.ecociente.validador.config;

import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Configuração do validador de fotos. Os nomes das variáveis de ambiente estão no application.properties. */
@Validated
@ConfigurationProperties(prefix = "app.validador")
public record PropriedadesValidador(
    @NotBlank @Size(min = 16) String chaveApi,
    String chaveApiAnterior,
    @NotEmpty List<String> hostsPermitidos,
    @DecimalMin("0") @DecimalMax("1") double limiarConfianca,
    @DecimalMin("0") @DecimalMax("1") Double limiarRevisao,
    String limiaresPorCategoria,
    @Positive int maxPedidosSimultaneos,
    @Positive long tamanhoMaximoBytes,
    @Positive int timeoutDownloadSegundos,
    @NotBlank String clipDir) {
}
