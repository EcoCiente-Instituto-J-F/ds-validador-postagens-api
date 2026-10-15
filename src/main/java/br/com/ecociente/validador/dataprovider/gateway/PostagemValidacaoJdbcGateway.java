package br.com.ecociente.validador.dataprovider.gateway;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import br.com.ecociente.validador.core.domain.DadosTriagem;
import br.com.ecociente.validador.core.domain.ResultadoVoto;
import br.com.ecociente.validador.core.domain.VinculoTrust;
import br.com.ecociente.validador.core.gateway.PostagemValidacaoGateway;

/** SQL direto sobre o schema do app: chama os procedures e lê as tabelas de postagens. */
@Component
public class PostagemValidacaoJdbcGateway implements PostagemValidacaoGateway {

  private final JdbcTemplate jdbc;

  public PostagemValidacaoJdbcGateway(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<DadosTriagem> buscarDadosTriagem(int postagemId) {
    return jdbc.query(
            "SELECT p.url_foto, c.nome_categoria FROM tb_postagens p"
                + " JOIN tb_lkp_categorias_residuos c ON c.id_categoria = p.categoria_id"
                + " WHERE p.id_postagem = ?",
            (rs, i) -> new DadosTriagem(rs.getString(1), rs.getString(2)),
            postagemId)
        .stream()
        .findFirst();
  }

  @Override
  public void gravarTriagem(int postagemId, Boolean aprovada, Double confianca) {
    jdbc.update(
        "UPDATE tb_postagens SET triagem_automatica_aprovada = ?, triagem_automatica_confianca = ?"
            + " WHERE id_postagem = ?",
        aprovada, confianca, postagemId);
  }

  @Override
  public Optional<Integer> buscarUsuarioAutor(int postagemId) {
    return jdbc.query(
            "SELECT usuario_id FROM tb_postagens WHERE id_postagem = ?",
            (rs, i) -> rs.getInt(1),
            postagemId)
        .stream()
        .findFirst();
  }

  @Override
  public boolean existePostagem(int postagemId) {
    Integer encontrados = jdbc.queryForObject(
        "SELECT COUNT(*) FROM tb_postagens WHERE id_postagem = ?", Integer.class, postagemId);
    return encontrados != null && encontrados > 0;
  }

  @Override
  public Integer buscarUsuarioSindico(int postagemId) {
    // LEFT JOIN: condomínio sem síndico devolve nulo, e o serviço recusa a decisão
    return jdbc.query(
            "SELECT s.usuario_id FROM tb_postagens p"
                + " JOIN tb_condominios c ON c.id_condominio = p.condominio_id"
                + " LEFT JOIN tb_sindicos s ON s.id_sindico = c.sindico_id"
                + " WHERE p.id_postagem = ?",
            (rs, i) -> (Integer) rs.getObject(1),
            postagemId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  @Override
  public ResultadoVoto registrarVoto(
      int postagemId, int usuarioId, String tipo, Integer motivoDenunciaId, String comentario) {
    jdbc.update(
        "CALL sp_processar_voto_postagem(?, ?, CAST(? AS varchar), CAST(? AS int), CAST(? AS varchar))",
        postagemId, usuarioId, tipo, motivoDenunciaId, comentario);
    return jdbc.queryForObject(
        "SELECT saldo_confianca, pontuacao_ativa FROM tb_postagens WHERE id_postagem = ?",
        (rs, i) -> new ResultadoVoto(rs.getInt(1), rs.getBoolean(2)),
        postagemId);
  }

  @Override
  public void registrarDecisao(int postagemId, boolean aprovar) {
    jdbc.update("CALL sp_decidir_postagem_analise(?, ?)", postagemId, aprovar);
  }

  @Override
  public List<VinculoTrust> buscarEnvolvidosTrust(int postagemId) {
    // JOIN no vínculo: quem saiu do condomínio (ou nunca entrou) não tem trust score a atualizar
    // ORDER BY: ordem fixa dos locks, evita deadlock entre /decisao e o job
    return jdbc.query(
        "SELECT DISTINCT p.condominio_id, e.usuario_id FROM tb_postagens p"
            + " CROSS JOIN LATERAL (SELECT p.usuario_id AS usuario_id"
            + "   UNION SELECT v.usuario_id FROM tb_rel_votos_postagens v WHERE v.postagem_id = p.id_postagem) e"
            + " JOIN tb_rel_usuarios_condominios r ON r.usuario_id = e.usuario_id AND r.condominio_id = p.condominio_id"
            + " WHERE p.id_postagem = ?"
            + " ORDER BY e.usuario_id",
        (rs, i) -> new VinculoTrust(rs.getInt(1), rs.getInt(2)),
        postagemId);
  }

  @Override
  public void atualizarTrustScore(int usuarioId, int condominioId) {
    jdbc.update("CALL sp_atualizar_trust_score(?, ?)", usuarioId, condominioId);
  }

  @Override
  public List<Integer> buscarJanelasVencidas(int limite) {
    return jdbc.queryForList(
        "SELECT id_postagem FROM tb_postagens WHERE resolvido_em IS NULL AND data_limite_analise <= now()"
            + " ORDER BY data_limite_analise LIMIT ?",
        Integer.class,
        limite);
  }

  @Override
  public void encerrarJanela(int postagemId) {
    jdbc.update("CALL sp_encerrar_janela_postagem(?)", postagemId);
  }
}
