package br.com.ecociente.validador.core.service;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import br.com.ecociente.validador.core.domain.ResultadoVoto;
import br.com.ecociente.validador.core.exception.NotFoundException;
import br.com.ecociente.validador.core.exception.PermissaoNegadaException;
import br.com.ecociente.validador.dataprovider.gateway.PostagemValidacaoJdbcGateway;

/**
 * Regras de votação, decisão do síndico e fechamento da janela. As regras de voto, histerese, fechamento e
 * trust_score vivem nos procedures do banco; aqui só os chamamos na ordem certa.
 */
@Service
public class PostagemValidacaoService {

  private static final String POSTAGEM_NAO_ENCONTRADA = "Postagem não encontrada.";

  private final PostagemValidacaoJdbcGateway gateway;

  public PostagemValidacaoService(PostagemValidacaoJdbcGateway gateway) {
    this.gateway = gateway;
  }

  @Transactional
  public ResultadoVoto votar(int postagemId, int usuarioId, String tipo, Integer motivoDenunciaId, String comentario) {
    int autor = gateway.buscarUsuarioAutor(postagemId)
        .orElseThrow(() -> new NotFoundException(POSTAGEM_NAO_ENCONTRADA));
    if (autor == usuarioId) {
      throw new PermissaoNegadaException("O autor não pode votar na própria postagem.");
    }
    return gateway.registrarVoto(postagemId, usuarioId, tipo, motivoDenunciaId, comentario);
  }

  @Transactional
  public void decidir(int postagemId, int usuarioId, boolean aprovar) {
    if (!gateway.existePostagem(postagemId)) {
      throw new NotFoundException(POSTAGEM_NAO_ENCONTRADA);
    }
    if (!Objects.equals(gateway.buscarUsuarioSindico(postagemId), usuarioId)) {
      throw new PermissaoNegadaException("Só o síndico do condomínio decide postagens em análise.");
    }
    gateway.registrarDecisao(postagemId, aprovar);
    atualizarTrustScores(postagemId);
  }

  @Transactional
  public void encerrarJanela(int postagemId) {
    gateway.encerrarJanela(postagemId);
    atualizarTrustScores(postagemId);
  }

  private void atualizarTrustScores(int postagemId) {
    // quem saiu do condomínio (ou nunca entrou) não tem trust score a atualizar: o gateway já filtra
    gateway.buscarEnvolvidosTrust(postagemId)
        .forEach(vinculo -> gateway.atualizarTrustScore(vinculo.usuarioId(), vinculo.condominioId()));
  }
}
