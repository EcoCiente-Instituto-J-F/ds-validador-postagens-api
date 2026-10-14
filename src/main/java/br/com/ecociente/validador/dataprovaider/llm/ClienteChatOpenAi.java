package br.com.ecociente.validador.dataprovaider.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Cliente de chat no formato OpenAI (/chat/completions): OpenAI, Groq, Ollama e o endpoint compatível do Gemini.
 * A resposta vem em JSON (response_format json_object) e quem chama lê os campos.
 */
public final class ClienteChatOpenAi {

  private final RestClient http;
  private final String modelo;

  public ClienteChatOpenAi(String urlBase, String chave, String modelo, Duration timeout) {
    SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
    fabrica.setConnectTimeout(timeout);
    fabrica.setReadTimeout(timeout);
    this.http = RestClient.builder()
        .baseUrl(urlBase)
        .requestFactory(fabrica)
        .defaultHeader("Authorization", "Bearer " + chave)
        .build();
    this.modelo = modelo;
  }

  /** Devolve o conteúdo da primeira escolha, que deve ser um objeto JSON. */
  public String completarJson(List<Map<String, Object>> mensagens) {
    JsonNode resposta = http.post()
        .uri("/chat/completions")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of(
            "model", modelo,
            "messages", mensagens,
            "response_format", Map.of("type", "json_object")))
        .retrieve()
        .body(JsonNode.class);
    if (resposta == null) {
      throw new IllegalStateException("Resposta vazia do provedor de chat");
    }
    return resposta.path("choices").path(0).path("message").path("content").asText();
  }
}
