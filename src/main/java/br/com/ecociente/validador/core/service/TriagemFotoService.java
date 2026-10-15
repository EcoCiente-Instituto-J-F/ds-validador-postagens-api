package br.com.ecociente.validador.core.service;

import java.awt.image.BufferedImage;
import java.util.List;

import org.springframework.stereotype.Service;

import br.com.ecociente.validador.core.domain.ClasseProbabilidade;
import br.com.ecociente.validador.core.domain.FotoCarregada;
import br.com.ecociente.validador.core.domain.ParametrosTriagem;
import br.com.ecociente.validador.core.domain.VeredictoFoto;
import br.com.ecociente.validador.core.exception.FotoException;
import br.com.ecociente.validador.core.imagem.DHash;
import br.com.ecociente.validador.dataprovider.foto.FotoHttpGateway;
import br.com.ecociente.validador.dataprovider.visao.ClassificadorClipGateway;

/** Triagem de uma foto: baixa, classifica, aplica a regra de pertinência e registra os indícios. */
@Service
public class TriagemFotoService {

  private final FotoHttpGateway fotos;
  private final ClassificadorClipGateway classificador;
  private final ParametrosTriagem parametros;

  public TriagemFotoService(FotoHttpGateway fotos, ClassificadorClipGateway classificador, ParametrosTriagem parametros) {
    this.fotos = fotos;
    this.classificador = classificador;
    this.parametros = parametros;
  }

  /** categoria já vem normalizada pelo catálogo. Falhas de rotina voltam como status, não como exceção. */
  public VeredictoFoto analisar(String urlFoto, String categoria) {
    FotoCarregada foto;
    try {
      foto = fotos.baixar(urlFoto);
    } catch (FotoException erro) {
      return VeredictoFoto.rejeitada(categoria, erro.getMessage(), erro.getStatus());
    }

    BufferedImage imagem = foto.imagem();
    List<ClasseProbabilidade> ranking = classificador.ranking(imagem);
    VeredictoFoto veredito = RegraPertinencia.decidir(
        categoria, ranking, parametros.limiarPara(categoria), parametros.limiarRevisao());
    List<String> sinais = foto.semExif() ? List.of("sem_exif") : List.of();
    return veredito.comSinais(DHash.calcular(imagem), sinais);
  }
}
