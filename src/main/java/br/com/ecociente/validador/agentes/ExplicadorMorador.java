package br.com.ecociente.validador.agentes;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.ecociente.validador.dataprovider.llm.ClienteChatOpenAi;

/**
 * Explica ao morador o resultado da triagem das fotos dele, usando só o contexto de triagens recentes.
 * Fica fora da API de propósito: a API é determinística e não depende de LLM. Quem monta o chat do app usa esta classe.
 */
public final class ExplicadorMorador {

  private static final String PROMPT_SISTEMA = """
      Você é o assistente de reciclagem do EcoCiente e explica a triagem automática das fotos.
      Leia a seção 'Triagens recentes de fotos': é a única fonte do que aconteceu com cada foto.
      Responda à pergunta do morador usando só o que consta ali.
      Responda em português, em até 3 frases, de forma gentil.
      Se não houver triagem, diga que nenhuma foto foi analisada; nunca invente um resultado.
      Se a foto aguarda revisão humana, diga que ela será revisada e que ainda não há resultado.
      Se a foto não foi aprovada, diga o que o modelo viu e sugira tentar de novo de mais perto e com boa luz.
      Responda só com um JSON: {"resposta": "..."}.
      """;

  private final ClienteChatOpenAi cliente;
  private final ContextoTriagens contexto;
  private final ObjectMapper mapper = new ObjectMapper();

  public ExplicadorMorador(ClienteChatOpenAi cliente, ContextoTriagens contexto) {
    this.cliente = cliente;
    this.contexto = contexto;
  }

  public String responder(String pergunta) {
    String sistema = PROMPT_SISTEMA + "\n## Triagens recentes de fotos\n" + contexto.texto();
    try {
      String conteudo = cliente.completarJson(List.of(
          Map.of("role", "system", "content", sistema),
          Map.of("role", "user", "content", pergunta)));
      JsonNode resposta = mapper.readTree(conteudo).get("resposta");
      return resposta == null ? "" : resposta.asText();
    } catch (Exception erro) {
      throw new IllegalStateException("Não foi possível obter a resposta do explicador", erro);
    }
  }
}
