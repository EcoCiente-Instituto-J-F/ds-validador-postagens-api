package br.com.ecociente.validador.core.service;

import java.util.concurrent.Semaphore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import br.com.ecociente.validador.core.domain.Categorias;
import br.com.ecociente.validador.core.domain.Classe;
import br.com.ecociente.validador.core.domain.DadosTriagem;
import br.com.ecociente.validador.core.domain.StatusTriagem;
import br.com.ecociente.validador.core.domain.VeredictoFoto;
import br.com.ecociente.validador.core.exception.CategoriaInvalidaException;
import br.com.ecociente.validador.core.exception.NotFoundException;
import br.com.ecociente.validador.core.exception.ServicoOcupadoException;

/**
 * Fluxo completo do POST /triagem: lê a postagem, valida a categoria, controla a vazão e grava o veredito.
 * A conexão com o banco não fica aberta durante o download e a inferência.
 */
@Service
public class TriagemPostagemService {

  private final PostagemValidacaoService postagens;
  private final TriagemFotoService triagemFoto;
  private final Semaphore vagas;

  public TriagemPostagemService(
      PostagemValidacaoService postagens,
      TriagemFotoService triagemFoto,
      @Value("${app.validador.max-pedidos-simultaneos}") int maxPedidosSimultaneos) {
    this.postagens = postagens;
    this.triagemFoto = triagemFoto;
    this.vagas = new Semaphore(maxPedidosSimultaneos);
  }

  public VeredictoFoto triar(int postagemId) {
    DadosTriagem dados = postagens.buscarDadosTriagem(postagemId)
        .orElseThrow(() -> new NotFoundException("Postagem não encontrada."));
    String categoria = categoriaDoCatalogo(dados.nomeCategoria());

    // backpressure: o modelo atende poucos pedidos por vez; acima disso a API responde 503 em vez de enfileirar
    if (!vagas.tryAcquire()) {
      throw new ServicoOcupadoException();
    }
    VeredictoFoto veredito;
    try {
      veredito = triagemFoto.analisar(dados.urlFoto(), categoria);
    } finally {
      vagas.release();
    }

    if (veredito.status() == StatusTriagem.OK) {
      postagens.gravarTriagem(postagemId, veredito);
    }
    return veredito;
  }

  private static String categoriaDoCatalogo(String nomeCategoria) {
    return Categorias.porSlug(Categorias.normalizar(nomeCategoria))
        .map(Classe::slug)
        .orElseThrow(() -> new CategoriaInvalidaException(nomeCategoria));
  }
}
