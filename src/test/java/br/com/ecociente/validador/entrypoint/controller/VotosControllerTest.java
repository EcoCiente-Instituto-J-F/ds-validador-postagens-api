package br.com.ecociente.validador.entrypoint.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import br.com.ecociente.validador.core.domain.ResultadoVoto;
import br.com.ecociente.validador.core.service.PostagemValidacaoService;
import br.com.ecociente.validador.entrypoint.exception.GlobalExceptionHandler;

class VotosControllerTest {

  private PostagemValidacaoService service;
  private MockMvc mvc;

  @BeforeEach
  void configurar() {
    service = mock(PostagemValidacaoService.class);
    LocalValidatorFactoryBean validador = new LocalValidatorFactoryBean();
    validador.afterPropertiesSet();
    mvc = MockMvcBuilders.standaloneSetup(new VotosController(service))
        .setControllerAdvice(new GlobalExceptionHandler())
        .setValidator(validador)
        .setMessageConverters(new MappingJackson2HttpMessageConverter(
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)))
        .build();
  }

  @Test
  void votoAprovarDevolveSaldo() throws Exception {
    when(service.votar(anyInt(), anyInt(), anyString(), any(), any())).thenReturn(new ResultadoVoto(3, true));

    mvc.perform(post("/v1/postagens/10/votos")
            .contentType("application/json")
            .content("{\"usuario_id\": 7, \"tipo\": \"aprovar\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.saldo_confianca").value(3))
        .andExpect(jsonPath("$.pontuacao_ativa").value(true));
  }

  @Test
  void tipoForaDoContratoVira422() throws Exception {
    mvc.perform(post("/v1/postagens/10/votos")
            .contentType("application/json")
            .content("{\"usuario_id\": 7, \"tipo\": \"curtir\"}"))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void votoDuplicadoVira409() throws Exception {
    when(service.votar(anyInt(), anyInt(), anyString(), any(), any()))
        .thenThrow(new DataIntegrityViolationException("dup", new SQLException("duplicate key", "23505")));

    mvc.perform(post("/v1/postagens/10/votos")
            .contentType("application/json")
            .content("{\"usuario_id\": 7, \"tipo\": \"aprovar\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("Usuário já votou nesta postagem."));
  }

  @Test
  void regraDoProcedureVira422ComMensagemDoBanco() throws Exception {
    when(service.votar(anyInt(), anyInt(), anyString(), any(), any()))
        .thenThrow(new org.springframework.dao.InvalidDataAccessApiUsageException("x",
            new SQLException("Janela comunitaria encerrou", "P0001")));

    mvc.perform(post("/v1/postagens/10/votos")
            .contentType("application/json")
            .content("{\"usuario_id\": 7, \"tipo\": \"aprovar\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.detail").value("Janela comunitaria encerrou"));
  }

  @Test
  void conexaoRecusadaAoAbrirTransacaoVira503() throws Exception {
    when(service.votar(anyInt(), anyInt(), anyString(), any(), any()))
        .thenThrow(new org.springframework.transaction.CannotCreateTransactionException("Could not open JDBC Connection"));

    mvc.perform(post("/v1/postagens/10/votos")
            .contentType("application/json")
            .content("{\"usuario_id\": 7, \"tipo\": \"aprovar\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "5"));
  }

  @Test
  void bancoIndisponivelVira503() throws Exception {
    when(service.votar(anyInt(), anyInt(), anyString(), any(), any()))
        .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("sem conexão"));

    mvc.perform(post("/v1/postagens/10/votos")
            .contentType("application/json")
            .content("{\"usuario_id\": 7, \"tipo\": \"aprovar\"}"))
        .andExpect(status().isServiceUnavailable());
  }
}
