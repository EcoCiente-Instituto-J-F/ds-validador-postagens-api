package br.com.ecociente.validador.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lê o header X-Api-Key e autentica o serviço chamador. Aceita a chave atual e, durante a rotação,
 * a anterior. A comparação leva o mesmo tempo qualquer que seja o caractere errado.
 */
public class FiltroChaveApi extends OncePerRequestFilter {

  static final String HEADER = "X-Api-Key";

  private final List<byte[]> chavesAceitas;

  public FiltroChaveApi(PropriedadesValidador propriedades) {
    this.chavesAceitas = java.util.stream.Stream.of(propriedades.chaveApi(), propriedades.chaveApiAnterior())
        .filter(chave -> chave != null && !chave.isBlank())
        .map(chave -> chave.getBytes(StandardCharsets.UTF_8))
        .toList();
  }

  @Override
  protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta, FilterChain cadeia)
      throws ServletException, IOException {
    String recebida = requisicao.getHeader(HEADER);
    if (recebida != null && chaveValida(recebida.getBytes(StandardCharsets.UTF_8))) {
      SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
          "servico", null, List.of(new SimpleGrantedAuthority("ROLE_SERVICO"))));
    }
    cadeia.doFilter(requisicao, resposta);
  }

  private boolean chaveValida(byte[] recebida) {
    boolean valida = false;
    for (byte[] chave : chavesAceitas) {
      valida |= MessageDigest.isEqual(recebida, chave); // sem curto-circuito: tempo constante
    }
    return valida;
  }
}
