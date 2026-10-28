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
 * Cobre leitura de ausentes, o RAISE dos procedures e a troca de nível do sp_atualizar_trust_score.
 */
@Tag("banco")
@Testcontainers(disabledWithoutDocker = true)
class PostagemValidacaoJdbcGatewayTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  private static JdbcTemplate jdbc;
  private static PostagemValidacaoJdbcGateway gateway;

  @BeforeAll
  static void aplicarSchema() throws SQLException {
    var dados = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    try (var conexao = dados.getConnection()) {
      ScriptUtils.executeSqlScript(conexao, new ClassPathResource("sql/ecociente_schema.sql"));
      ScriptUtils.executeSqlScript(conexao, new ClassPathResource("sql/seed_lookups.sql"));
    }
    jdbc = new JdbcTemplate(dados);
    gateway = new PostagemValidacaoJdbcGateway(jdbc);
  }

  @Test
  void schemaSobeComOsNiveisDeConfianca() {
    assertThat(jdbc.queryForObject("SELECT count(*) FROM tb_lkp_niveis_confianca", Integer.class)).isEqualTo(3);
  }

  @Test
  void moradorComumQueAtingeOLimiarViraPessoaConfiavel() {
    int condominio = criarCondominio();
    int usuario = criarUsuario();
    vincular(usuario, condominio, "morador_comum");
    criarPostagensAprovadas(usuario, condominio, 100); // 100 * 2 = 200, exatamente o limiar

    gateway.atualizarTrustScore(usuario, condominio);

    assertThat(nivel(usuario, condominio)).isEqualTo("pessoa_confiavel");
  }

  @Test
  void pessoaConfiavelAbaixoDoLimiarVoltaAMoradorComum() {
    int condominio = criarCondominio();
    int usuario = criarUsuario();
    vincular(usuario, condominio, "pessoa_confiavel");

    gateway.atualizarTrustScore(usuario, condominio);

    assertThat(nivel(usuario, condominio)).isEqualTo("morador_comum");
  }

  @Test
  void sindicoNaoEPromovidoNemRebaixado() {
    int condominio = criarCondominio();
    int comScore = criarUsuario();
    int semScore = criarUsuario();
    vincular(comScore, condominio, "sindico");
    vincular(semScore, condominio, "sindico");
    criarPostagensAprovadas(comScore, condominio, 100);

    gateway.atualizarTrustScore(comScore, condominio);
    gateway.atualizarTrustScore(semScore, condominio);

    assertThat(nivel(comScore, condominio)).isEqualTo("sindico");
    assertThat(nivel(semScore, condominio)).isEqualTo("sindico");
  }

  @Test
  void denunciaEmPostagemPendenteOuDeOutroCondominioNaoConta() {
    int condominio = criarCondominio();
    int outroCondominio = criarCondominio();
    int autor = criarUsuario();
    int denunciante = criarUsuario();
    vincular(denunciante, condominio, "morador_comum");
    denunciar(denunciante, criarPostagem(autor, condominio, "em_analise", false));
    denunciar(denunciante, criarPostagem(autor, outroCondominio, "reprovada", true));

    gateway.atualizarTrustScore(denunciante, condominio);

    assertThat(jdbc.queryForObject(
        "SELECT denuncias_realizadas FROM tb_rel_usuarios_condominios WHERE usuario_id = ? AND condominio_id = ?",
        Integer.class, denunciante, condominio)).isZero();
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

  private static int criarCondominio() {
    return jdbc.queryForObject(
        "INSERT INTO tb_condominios (nome_condominio, tipo_condominio_id)"
            + " VALUES ('Condominio teste', (SELECT min(id_tipo_condominio) FROM tb_lkp_tipos_condominios))"
            + " RETURNING id_condominio",
        Integer.class);
  }

  private static int criarUsuario() {
    int endereco = jdbc.queryForObject("INSERT INTO tb_enderecos DEFAULT VALUES RETURNING id_endereco", Integer.class);
    return jdbc.queryForObject(
        "INSERT INTO tb_usuarios (nome_usuario, email_usuario, senha_hash, tipo_usuario_id, endereco_id)"
            + " VALUES ('Teste', 'teste@ecociente.dev', 'x', (SELECT min(id_tipo_usuario) FROM tb_lkp_tipos_usuarios), ?)"
            + " RETURNING id_usuario",
        Integer.class, endereco);
  }

  private static void vincular(int usuario, int condominio, String nivel) {
    jdbc.update(
        "INSERT INTO tb_rel_usuarios_condominios (usuario_id, condominio_id, nivel_confianca_id, trust_score,"
            + " postagens_validadas_sem_contestacao, denuncias_realizadas, denuncias_procedentes)"
            + " VALUES (?, ?, (SELECT id_nivel_confianca FROM tb_lkp_niveis_confianca WHERE nome_nivel = ?), 0, 0, 0, 0)",
        usuario, condominio, nivel);
  }

  private static void criarPostagensAprovadas(int usuario, int condominio, int quantidade) {
    jdbc.update(
        "INSERT INTO tb_postagens (usuario_id, condominio_id, categoria_id, url_foto, hash_foto, capturada_em,"
            + " status_validacao_id, resolvido_em)"
            + " SELECT ?, ?, (SELECT min(id_categoria) FROM tb_lkp_categorias_residuos), 'x', md5(random()::text || g),"
            + " now() - INTERVAL '1 hour',"
            + " (SELECT id_status_validacao FROM tb_lkp_status_validacoes_postagens WHERE nome_status = 'aprovada'), now()"
            + " FROM generate_series(1, ?) g",
        usuario, condominio, quantidade);
  }

  private static int criarPostagem(int usuario, int condominio, String status, boolean resolvida) {
    return jdbc.queryForObject(
        "INSERT INTO tb_postagens (usuario_id, condominio_id, categoria_id, url_foto, hash_foto, capturada_em,"
            + " status_validacao_id, resolvido_em)"
            + " VALUES (?, ?, (SELECT min(id_categoria) FROM tb_lkp_categorias_residuos), 'x', md5(random()::text),"
            + " now() - INTERVAL '1 hour',"
            + " (SELECT id_status_validacao FROM tb_lkp_status_validacoes_postagens WHERE nome_status = ?),"
            + " CASE WHEN ? THEN now() END)"
            + " RETURNING id_postagem",
        Integer.class, usuario, condominio, status, resolvida);
  }

  private static void denunciar(int usuario, int postagem) {
    jdbc.update(
        "INSERT INTO tb_rel_votos_postagens (postagem_id, usuario_id, tipo_voto_id, peso_aplicado)"
            + " VALUES (?, ?, (SELECT id_tipo_voto FROM tb_lkp_tipos_votos_postagens WHERE nome_tipo = 'denunciar'), 1)",
        postagem, usuario);
  }

  private static String nivel(int usuario, int condominio) {
    return jdbc.queryForObject(
        "SELECT nc.nome_nivel FROM tb_rel_usuarios_condominios r"
            + " JOIN tb_lkp_niveis_confianca nc ON nc.id_nivel_confianca = r.nivel_confianca_id"
            + " WHERE r.usuario_id = ? AND r.condominio_id = ?",
        String.class, usuario, condominio);
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
