package br.com.ecociente.validador.config;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Rotas de serviço (/v1/**) exigem X-Api-Key. Saúde, métricas e a documentação ficam abertas: só o cluster as
 * alcança (o Service é ClusterIP).
 */
@Configuration
public class SegurancaConfig {

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http, PropriedadesValidador propriedades) throws Exception {
    return http
        .csrf(csrf -> csrf.disable())
        .sessionManagement(sessao -> sessao.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/saude", "/metrics", "/docs/**", "/v3/api-docs/**", "/actuator/health/**", "/error")
            .permitAll()
            .anyRequest().authenticated())
        .addFilterBefore(new FiltroChaveApi(propriedades), UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(erros -> erros.authenticationEntryPoint((requisicao, resposta, excecao) ->
            escreverErro(resposta)))
        .build();
  }

  private static void escreverErro(HttpServletResponse resposta) throws IOException {
    resposta.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    resposta.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
    resposta.getWriter().write("{\"detail\":\"X-Api-Key inválida.\"}");
  }
}
