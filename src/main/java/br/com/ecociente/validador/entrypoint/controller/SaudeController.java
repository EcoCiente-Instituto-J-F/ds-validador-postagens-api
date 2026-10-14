package br.com.ecociente.validador.entrypoint.controller;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.ecociente.validador.entrypoint.metricas.MetricasTriagem;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;

/** Readiness e liveness do Kubernetes (/saude) e métricas Prometheus (/metrics), ambos sem chave. */
@RestController
public class SaudeController {

  private static final MediaType PROMETHEUS = MediaType.parseMediaType("text/plain; version=0.0.4; charset=utf-8");

  private final PrometheusMeterRegistry registro;

  public SaudeController(PrometheusMeterRegistry registro) {
    this.registro = registro;
  }

  @GetMapping("/saude")
  public Map<String, String> saude() {
    return Map.of("status", "ok");
  }

  @GetMapping(value = "/metrics", produces = "text/plain")
  public ResponseEntity<String> metricas() {
    return ResponseEntity.ok().contentType(PROMETHEUS).body(registro.scrape());
  }
}
