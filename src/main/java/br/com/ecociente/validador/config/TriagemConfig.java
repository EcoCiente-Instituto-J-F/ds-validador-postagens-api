package br.com.ecociente.validador.config;

import java.util.HashMap;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.ecociente.validador.core.domain.Categorias;
import br.com.ecociente.validador.core.domain.ParametrosTriagem;

@Configuration
public class TriagemConfig {

  @Bean
  public ParametrosTriagem parametrosTriagem(PropriedadesValidador propriedades) throws JsonProcessingException {
    return new ParametrosTriagem(
        propriedades.limiarConfianca(),
        limiaresPorCategoria(propriedades.limiaresPorCategoria()),
        propriedades.limiarRevisao());
  }

  /** JSON no ambiente: {"vidro": 0.6}. Vazio = nenhum limiar próprio. */
  static Map<String, Double> limiaresPorCategoria(String json) throws JsonProcessingException {
    if (json == null || json.isBlank()) {
      return Map.of();
    }
    Map<String, Double> lidos = new ObjectMapper().readValue(json, new TypeReference<Map<String, Double>>() { });
    Map<String, Double> normalizados = new HashMap<>();
    for (Map.Entry<String, Double> entrada : lidos.entrySet()) {
      String slug = Categorias.normalizar(entrada.getKey());
      if (Categorias.porSlug(slug).isEmpty()) {
        throw new IllegalStateException("limiares-por-categoria: categoria desconhecida: " + entrada.getKey());
      }
      if (entrada.getValue() < 0 || entrada.getValue() > 1) {
        throw new IllegalStateException("limiares-por-categoria: " + entrada.getKey() + " deve estar entre 0 e 1");
      }
      normalizados.put(slug, entrada.getValue());
    }
    return Map.copyOf(normalizados);
  }
}
