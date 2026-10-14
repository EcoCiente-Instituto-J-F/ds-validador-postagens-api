package br.com.ecociente.validador.config;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ai.onnxruntime.OrtException;
import br.com.ecociente.validador.core.gateway.ClassificadorGateway;
import br.com.ecociente.validador.dataprovaider.visao.CabecaTreinada;
import br.com.ecociente.validador.dataprovaider.visao.ClassificadorCabecaGateway;
import br.com.ecociente.validador.dataprovaider.visao.ClassificadorClipGateway;
import br.com.ecociente.validador.dataprovaider.visao.ModeloClip;

/** Carrega o CLIP uma vez na subida (como o serviço antigo): o pod só abre a porta com o modelo pronto. */
@Configuration
public class VisaoConfig {

  @Bean(destroyMethod = "close")
  public ModeloClip modeloClip(PropriedadesValidador propriedades) throws OrtException, IOException {
    return new ModeloClip(Path.of(propriedades.clipDir()));
  }

  @Bean
  public ClassificadorGateway classificadorGateway(
      ModeloClip modelo, PropriedadesValidador propriedades) throws IOException {
    String caminhoCabeca = propriedades.cabecaTreinada();
    if (caminhoCabeca == null || caminhoCabeca.isBlank()) {
      return new ClassificadorClipGateway(modelo);
    }
    CabecaTreinada cabeca = CabecaTreinada.carregar(Path.of(caminhoCabeca));
    if (!ModeloClip.MODELO.equals(cabeca.modelo())) {
      throw new IllegalStateException(
          "A cabeça foi treinada com " + cabeca.modelo() + ", mas o modelo carregado é " + ModeloClip.MODELO + ".");
    }
    return new ClassificadorCabecaGateway(modelo, cabeca);
  }
}
