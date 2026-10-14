package br.com.ecociente.validador.entrypoint.exception;

import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.postgresql.util.PSQLException;

import br.com.ecociente.validador.core.domain.StatusTriagem;
import br.com.ecociente.validador.core.exception.CategoriaInvalidaException;
import br.com.ecociente.validador.core.exception.FotoRecusadaException;
import br.com.ecociente.validador.core.exception.NotFoundException;
import br.com.ecociente.validador.core.exception.PermissaoNegadaException;
import br.com.ecociente.validador.core.exception.ServicoOcupadoException;
import br.com.ecociente.validador.entrypoint.dto.ErroResposta;
import jakarta.validation.ConstraintViolationException;

/**
 * Traduz as falhas para o contrato HTTP do serviço: {"detail": "..."} com o status que o app já trata.
 * Os procedures do banco sinalizam regra recusada com RAISE (SQLSTATE P0001): a mensagem vai ao cliente.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final String RETRY_AFTER = "5";

  @ExceptionHandler(NotFoundException.class)
  public ResponseEntity<ErroResposta> naoEncontrada(NotFoundException erro) {
    return resposta(HttpStatus.NOT_FOUND, erro.getMessage());
  }

  @ExceptionHandler(PermissaoNegadaException.class)
  public ResponseEntity<ErroResposta> permissaoNegada(PermissaoNegadaException erro) {
    return resposta(HttpStatus.FORBIDDEN, erro.getMessage());
  }

  @ExceptionHandler(CategoriaInvalidaException.class)
  public ResponseEntity<ErroResposta> categoriaInvalida(CategoriaInvalidaException erro) {
    return resposta(HttpStatus.UNPROCESSABLE_ENTITY, erro.getMessage());
  }

  @ExceptionHandler(FotoRecusadaException.class)
  public ResponseEntity<ErroResposta> fotoRecusada(FotoRecusadaException erro) {
    HttpStatus status = erro.getStatus() == StatusTriagem.FOTO_INDISPONIVEL
        ? HttpStatus.BAD_GATEWAY
        : HttpStatus.UNPROCESSABLE_ENTITY;
    return resposta(status, erro.getMessage());
  }

  @ExceptionHandler(ServicoOcupadoException.class)
  public ResponseEntity<ErroResposta> servicoOcupado(ServicoOcupadoException erro) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER)
        .body(new ErroResposta(erro.getMessage()));
  }

  @ExceptionHandler({
      MethodArgumentNotValidException.class,
      HandlerMethodValidationException.class,
      ConstraintViolationException.class,
      MethodArgumentTypeMismatchException.class,
      HttpMessageNotReadableException.class
  })
  public ResponseEntity<ErroResposta> entradaInvalida(Exception erro) {
    return resposta(HttpStatus.UNPROCESSABLE_ENTITY, "Dados inválidos: " + erro.getMessage());
  }

  @ExceptionHandler(DataAccessException.class)
  public ResponseEntity<ErroResposta> erroDeBanco(DataAccessException erro) {
    SQLException causa = sqlException(erro);
    String estado = causa == null ? null : causa.getSQLState();

    if ("23505".equals(estado)) { // UNIQUE: o usuário já votou nesta postagem
      return resposta(HttpStatus.CONFLICT, "Usuário já votou nesta postagem.");
    }
    if ("23503".equals(estado)) { // FK: motivo de denúncia inexistente
      return resposta(HttpStatus.UNPROCESSABLE_ENTITY, "Referência inválida (motivo de denúncia inexistente?).");
    }
    if ("P0001".equals(estado)) { // RAISE EXCEPTION dentro do procedure: regra de negócio recusada
      return resposta(HttpStatus.UNPROCESSABLE_ENTITY, mensagemDoProcedure(causa));
    }
    if (estaIndisponivel(erro, estado)) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
          .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER)
          .body(new ErroResposta("Banco de dados indisponível; tente de novo."));
    }
    log.error("Erro de banco não tratado (SQLSTATE {})", estado, erro);
    return resposta(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno.");
  }

  private static boolean estaIndisponivel(DataAccessException erro, String estado) {
    return erro instanceof DataAccessResourceFailureException
        || erro instanceof CannotGetJdbcConnectionException
        || erro instanceof TransientDataAccessException
        || (estado != null && estado.startsWith("08")); // classe 08: exceção de conexão
  }

  private static String mensagemDoProcedure(SQLException causa) {
    if (causa instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
      return psql.getServerErrorMessage().getMessage();
    }
    return causa.getMessage();
  }

  private static SQLException sqlException(Throwable erro) {
    for (Throwable atual = erro; atual != null; atual = atual.getCause()) {
      if (atual instanceof SQLException sql) {
        return sql;
      }
    }
    return null;
  }

  private static ResponseEntity<ErroResposta> resposta(HttpStatus status, String mensagem) {
    return ResponseEntity.status(status).body(new ErroResposta(mensagem));
  }
}
