package br.com.ecociente.validador.core.gateway;

import java.util.List;
import java.util.Optional;

import br.com.ecociente.validador.core.domain.DadosTriagem;
import br.com.ecociente.validador.core.domain.ResultadoVoto;
import br.com.ecociente.validador.core.domain.VinculoTrust;

/** Acesso ao banco do app: triagem, votos, decisão do síndico e fechamento das janelas. */
public interface PostagemValidacaoGateway {

  Optional<DadosTriagem> buscarDadosTriagem(int postagemId);

  void gravarTriagem(int postagemId, Boolean aprovada, Double confianca);

  Optional<Integer> buscarUsuarioAutor(int postagemId);

  boolean existePostagem(int postagemId);

  /** Usuário do síndico do condomínio da postagem, ou nulo quando o condomínio não tem síndico. */
  Integer buscarUsuarioSindico(int postagemId);

  ResultadoVoto registrarVoto(int postagemId, int usuarioId, String tipo, Integer motivoDenunciaId, String comentario);

  void registrarDecisao(int postagemId, boolean aprovar);

  List<VinculoTrust> buscarEnvolvidosTrust(int postagemId);

  void atualizarTrustScore(int usuarioId, int condominioId);

  List<Integer> buscarJanelasVencidas(int limite);

  void encerrarJanela(int postagemId);
}
