package br.com.ecociente.validador.dataprovaider.visao;

import java.awt.image.BufferedImage;
import java.util.List;

import br.com.ecociente.validador.core.domain.ClasseProbabilidade;
import br.com.ecociente.validador.core.gateway.ClassificadorGateway;

/** Mesmo contrato do zero-shot, mas com a cabeça treinada no lugar dos prompts. */
public final class ClassificadorCabecaGateway implements ClassificadorGateway {

  private final ModeloClip modelo;
  private final CabecaTreinada cabeca;

  public ClassificadorCabecaGateway(ModeloClip modelo, CabecaTreinada cabeca) {
    this.modelo = modelo;
    this.cabeca = cabeca;
  }

  @Override
  public synchronized List<ClasseProbabilidade> ranking(BufferedImage imagem) {
    return cabeca.ranking(modelo.embeddingImagem(imagem));
  }
}
