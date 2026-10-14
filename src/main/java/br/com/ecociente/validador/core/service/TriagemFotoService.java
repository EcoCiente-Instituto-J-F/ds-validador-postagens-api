package br.com.ecociente.validador.core.service;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import br.com.ecociente.validador.core.domain.Categorias;
import br.com.ecociente.validador.core.domain.ClasseProbabilidade;
import br.com.ecociente.validador.core.domain.FotoCarregada;
import br.com.ecociente.validador.core.domain.ParametrosTriagem;
import br.com.ecociente.validador.core.domain.StatusTriagem;
import br.com.ecociente.validador.core.domain.VeredictoFoto;
import br.com.ecociente.validador.core.exception.FotoIndisponivelException;
import br.com.ecociente.validador.core.exception.FotoInvalidaException;
import br.com.ecociente.validador.core.exception.PedidoFotoInvalidoException;
import br.com.ecociente.validador.core.gateway.ClassificadorGateway;
import br.com.ecociente.validador.core.gateway.FotoGateway;
import br.com.ecociente.validador.core.gateway.SegundaOpiniaoGateway;
import br.com.ecociente.validador.core.imagem.DHash;
import br.com.ecociente.validador.core.imagem.SinaisFoto;

/** Triagem de uma foto: baixa, classifica, aplica a regra de pertinência e registra os indícios. */
@Service
public class TriagemFotoService {

  private final FotoGateway fotos;
  private final ClassificadorGateway classificador;
  private final ObjectProvider<SegundaOpiniaoGateway> segundaOpiniao;
  private final ParametrosTriagem parametros;

  public TriagemFotoService(
      FotoGateway fotos,
      ClassificadorGateway classificador,
      ObjectProvider<SegundaOpiniaoGateway> segundaOpiniao,
      ParametrosTriagem parametros) {
    this.fotos = fotos;
    this.classificador = classificador;
    this.segundaOpiniao = segundaOpiniao;
    this.parametros = parametros;
  }

  /** categoria já vem normalizada pelo catálogo. Falhas de rotina voltam como status, não como exceção. */
  public VeredictoFoto analisar(String urlFoto, String categoria) {
    FotoCarregada foto;
    try {
      foto = fotos.baixar(urlFoto);
    } catch (PedidoFotoInvalidoException erro) {
      return VeredictoFoto.rejeitada(categoria, erro.getMessage(), StatusTriagem.PEDIDO_INVALIDO);
    } catch (FotoIndisponivelException erro) {
      return VeredictoFoto.rejeitada(categoria, erro.getMessage(), StatusTriagem.FOTO_INDISPONIVEL);
    } catch (FotoInvalidaException erro) {
      return VeredictoFoto.rejeitada(categoria, erro.getMessage(), StatusTriagem.OK);
    }

    BufferedImage imagem = foto.imagem();
    List<ClasseProbabilidade> ranking = classificador.ranking(imagem);
    VeredictoFoto veredito = RegraPertinencia.decidir(
        categoria, ranking, parametros.limiarPara(categoria), parametros.limiarRevisao());

    SegundaOpiniaoGateway opiniao = segundaOpiniao.getIfAvailable();
    if (veredito.pertinente() == null && opiniao != null) {
      veredito = consultarSegundaOpiniao(veredito, opiniao, imagem);
    }

    List<String> sinais = new ArrayList<>();
    if (SinaisFoto.temPadraoDeTela(imagem)) {
      sinais.add("padrao_de_tela");
    }
    if (foto.semExif()) {
      sinais.add("sem_exif");
    }
    return veredito.comSinais(DHash.calcular(imagem), sinais);
  }

  private VeredictoFoto consultarSegundaOpiniao(VeredictoFoto veredito, SegundaOpiniaoGateway opiniao, BufferedImage imagem) {
    Optional<Boolean> parecer = opiniao.avaliar(imagem, veredito.categoriaInformada());
    if (parecer.isEmpty()) { // provedor falhou: segue para revisão humana
      return veredito;
    }
    String nome = Categorias.categoria(veredito.categoriaInformada()).nome();
    String mensagem = parecer.get()
        ? "Foto compatível com " + nome + " (confirmada em segunda análise)."
        : "Não deu para confirmar que a foto é de " + nome + ". Tente de mais perto e com boa luz.";
    return veredito.comSegundaOpiniao(parecer.get(), mensagem);
  }
}
