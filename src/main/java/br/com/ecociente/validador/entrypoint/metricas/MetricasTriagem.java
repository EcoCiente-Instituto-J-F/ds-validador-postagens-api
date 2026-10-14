package br.com.ecociente.validador.entrypoint.metricas;

import org.springframework.stereotype.Component;

import br.com.ecociente.validador.core.domain.VeredictoFoto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Métricas do serviço, com os mesmos nomes do serviço antigo: validacoes_total{categoria,status,pertinente},
 * validacao_segundos, confianca_modelo{categoria} e pedidos_rejeitados_total.
 * A confiança média caindo sem mudança de modelo indica que mudou o tipo de foto que chega.
 */
@Component
public class MetricasTriagem {

  private final MeterRegistry registro;
  private final Counter rejeitados;

  public MetricasTriagem(MeterRegistry registro) {
    this.registro = registro;
    this.rejeitados = Counter.builder("pedidos_rejeitados")
        .description("Pedidos recusados com 503 por excesso de carga.")
        .register(registro);
  }

  public void registrar(VeredictoFoto veredito, double segundos) {
    Counter.builder("validacoes")
        .description("Vereditos por categoria, status e pertinência.")
        .tags("categoria", veredito.categoriaInformada(), "status", veredito.status().getValor(),
            "pertinente", rotulo(veredito.pertinente()))
        .register(registro)
        .increment();
    DistributionSummary.builder("validacao_segundos")
        .description("Tempo de cada validação (download + modelo).")
        .register(registro)
        .record(segundos);
    if (veredito.confianca() != null) {
      DistributionSummary.builder("confianca_modelo")
          .description("Confiança do modelo (0–100).")
          .tag("categoria", veredito.categoriaInformada())
          .serviceLevelObjectives(10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
          .register(registro)
          .record(veredito.confianca());
    }
  }

  public void pedidoRejeitado() {
    rejeitados.increment();
  }

  private static String rotulo(Boolean pertinente) {
    if (pertinente == null) {
      return "sem_veredito";
    }
    return pertinente ? "sim" : "nao";
  }
}
