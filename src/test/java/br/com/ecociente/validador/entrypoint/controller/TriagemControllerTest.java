package br.com.ecociente.validador.entrypoint.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import br.com.ecociente.validador.core.domain.Alternativa;
import br.com.ecociente.validador.core.domain.StatusTriagem;
import br.com.ecociente.validador.core.domain.VeredictoFoto;
import br.com.ecociente.validador.core.exception.NotFoundException;
import br.com.ecociente.validador.core.exception.ServicoOcupadoException;
import br.com.ecociente.validador.core.service.TriagemPostagemService;
import br.com.ecociente.validador.entrypoint.exception.GlobalExceptionHandler;
import br.com.ecociente.validador.entrypoint.metricas.MetricasTriagem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class TriagemControllerTest {

  private TriagemPostagemService triagem;
  private MockMvc mvc;

  @BeforeEach
  void configurar() {
    triagem = mock(TriagemPostagemService.class);
    var metricas = new MetricasTriagem(new SimpleMeterRegistry());
    LocalValidatorFactoryBean validador = new LocalValidatorFactoryBean();
    validador.afterPropertiesSet();
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
        .setPropertyNamingStrategy(com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);
    mvc = MockMvcBuilders.standaloneSetup(new TriagemController(triagem, metricas, mapper))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setValidator(validador)
        .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper))
        .build();
  }

  @Test
  void fotoOkDevolveContratoEmSnakeCase() throws Exception {
    when(triagem.triar(7)).thenReturn(new VeredictoFoto(StatusTriagem.OK, true, "vidro", "vidro", 87.42,
        "f0e4c2a1b3d59687", List.of("sem_exif"), List.of(new Alternativa("metal", 9.1)), "Foto compatível com vidro."));

    mvc.perform(post("/v1/postagens/7/triagem"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ok"))
        .andExpect(jsonPath("$.categoria_informada").value("vidro"))
        .andExpect(jsonPath("$.hash_foto").value("f0e4c2a1b3d59687"))
        .andExpect(jsonPath("$.alternativas[0].categoria").value("metal"));
  }

  @Test
  void pedidoInvalidoVira422ComMensagem() throws Exception {
    when(triagem.triar(anyInt())).thenReturn(new VeredictoFoto(StatusTriagem.PEDIDO_INVALIDO, null, "vidro", null, null,
        null, List.of(), List.of(), "Host não permitido: x."));

    mvc.perform(post("/v1/postagens/7/triagem"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value("Host não permitido: x."));
  }

  @Test
  void storageForaVira502() throws Exception {
    when(triagem.triar(anyInt())).thenReturn(new VeredictoFoto(StatusTriagem.FOTO_INDISPONIVEL, null, "vidro", null,
        null, null, List.of(), List.of(), "O storage respondeu HTTP 404."));

    mvc.perform(post("/v1/postagens/7/triagem")).andExpect(status().isBadGateway());
  }

  @Test
  void postagemInexistenteVira404() throws Exception {
    when(triagem.triar(anyInt())).thenThrow(new NotFoundException("Postagem não encontrada."));

    mvc.perform(post("/v1/postagens/7/triagem"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("Postagem não encontrada."));
  }

  @Test
  void categoriaForaDoCatalogoVira422() throws Exception {
    when(triagem.triar(anyInt())).thenThrow(new br.com.ecociente.validador.core.exception.CategoriaInvalidaException("Eletrônicos"));

    mvc.perform(post("/v1/postagens/7/triagem"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value("Categoria fora do catálogo do validador: Eletrônicos"));
  }

  @Test
  void servicoOcupadoVira503ComRetryAfter() throws Exception {
    when(triagem.triar(anyInt())).thenThrow(new ServicoOcupadoException());

    mvc.perform(post("/v1/postagens/7/triagem"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "5"));
  }

}
