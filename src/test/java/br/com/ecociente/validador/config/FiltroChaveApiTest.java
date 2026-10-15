package br.com.ecociente.validador.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import br.com.ecociente.validador.fixtures.Propriedades;

class FiltroChaveApiTest {

  @AfterEach
  void limpar() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void chaveAtualAutentica() throws Exception {
    autenticar("chave-de-teste-com-16-chars");

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
  }

  @Test
  void chaveAnteriorAindaAutenticaDuranteARotacao() throws Exception {
    var base = Propriedades.padrao(List.of("localhost"));
    var propriedades = new PropriedadesValidador(base.chaveApi(), "chave-antiga-com-16-chars", base.hostsPermitidos(),
        0.5, null, "", 8, 10L, 5, base.clipDir());
    var requisicao = new MockHttpServletRequest("POST", "/v1/postagens/1/triagem");
    requisicao.addHeader("X-Api-Key", "chave-antiga-com-16-chars");

    new FiltroChaveApi(propriedades).doFilter(requisicao, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
  }

  @Test
  void chaveErradaOuAusenteNaoAutentica() throws Exception {
    autenticar("chave-errada-com-16-chars!");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

    autenticar(null);
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }

  private static void autenticar(String chave) throws Exception {
    var requisicao = new MockHttpServletRequest("POST", "/v1/postagens/1/triagem");
    if (chave != null) {
      requisicao.addHeader("X-Api-Key", chave);
    }
    new FiltroChaveApi(Propriedades.padrao(List.of("localhost")))
        .doFilter(requisicao, new MockHttpServletResponse(), new MockFilterChain());
  }
}
