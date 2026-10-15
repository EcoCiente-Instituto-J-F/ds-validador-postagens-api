package br.com.ecociente.validador.dataprovider.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integração com PostgreSQL de verdade (Testcontainers) usando o schema do app.
 * Precisa de Docker: sem ele o Testcontainers pula a classe. Roda com ./gradlew testeBanco.
 * Cobre os caminhos que não exigem massa de dados: leitura de ausentes e o RAISE dos procedures.
 */
@Tag("banco")
@Testcontainers(disabledWithoutDocker = true)
class PostagemValidacaoJdbcGatewayTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static PostagemValidacaoJdbcGateway gateway;

  @BeforeAll
  static void aplicarSchema() throws SQLException {
    var dados = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    try (var conexao = dados.getConnection()) {
      ScriptUtils.executeSqlScript(conexao, new ClassPathResource("sql/ecociente_schema.sql"));
      ScriptUtils.executeSqlScript(conexao, new ClassPathResource("sql/seed_lookups.sql"));
    }
    gateway = new PostagemValidacaoJdbcGateway(new JdbcTemplate(dados));
  }

  @Test
  void schemaSobeComOsNiveisDeConfianca() {
    assertThat(new JdbcTemplate(new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
        .queryForObject("SELECT count(*) FROM tb_lkp_niveis_confianca", Integer.class)).isEqualTo(3);
  }

  @Test
  void postagemInexistenteNaoTemDadosDeTriagem() {
    assertThat(gateway.buscarDadosTriagem(999_999)).isEmpty();
    assertThat(gateway.existePostagem(999_999)).isFalse();
    assertThat(gateway.buscarUsuarioAutor(999_999)).isEmpty();
  }

  @Test
  void procedureRecusaPostagemInexistenteComMensagemDoBanco() {
    assertThatThrownBy(() -> gateway.encerrarJanela(999_999))
        .isInstanceOf(DataAccessException.class)
        .satisfies(erro -> assertThat(sqlState(erro)).isEqualTo("P0001"));
  }

  private static String sqlState(Throwable erro) {
    for (Throwable atual = erro; atual != null; atual = atual.getCause()) {
      if (atual instanceof SQLException sql) {
        return sql.getSQLState();
      }
    }
    return null;
  }
}
