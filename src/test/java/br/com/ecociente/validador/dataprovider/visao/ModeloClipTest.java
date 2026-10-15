package br.com.ecociente.validador.dataprovider.visao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import br.com.ecociente.validador.core.domain.Categorias;

/** Carrega o CLIP de verdade (ONNX): precisa dos arquivos em CLIP_DIR. Roda com ./gradlew testeModelo. */
@Tag("modelo")
class ModeloClipTest {

  private static final Path CLIP_DIR = Path.of(System.getenv().getOrDefault("CLIP_DIR", "modelos/clip-vit-base-patch32"));
  private static ModeloClip modelo;

  @BeforeAll
  static void carregar() throws Exception {
    assumeTrue(Files.exists(CLIP_DIR.resolve("vision_model.onnx")), "precisa dos arquivos ONNX do CLIP em CLIP_DIR");
    modelo = new ModeloClip(CLIP_DIR);
  }

  @AfterAll
  static void descarregar() {
    if (modelo != null) {
      modelo.close();
    }
  }

  @Test
  void embeddingsTemDimensao512() {
    assertThat(modelo.embeddingTexto("a selfie")).hasSize(512);
    assertThat(modelo.embeddingImagem(new BufferedImage(300, 200, BufferedImage.TYPE_INT_RGB))).hasSize(512);
  }

  @Test
  void rankingCobreTodasAsClassesComProbabilidadesQueSomamUm() {
    var classificador = new ClassificadorClipGateway(modelo);

    var ranking = classificador.ranking(new BufferedImage(224, 224, BufferedImage.TYPE_INT_RGB));

    assertThat(ranking).hasSize(Categorias.TODAS_AS_CLASSES.size());
    assertThat(ranking.stream().mapToDouble(c -> c.probabilidade()).sum()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
    assertThat(ranking.get(0).probabilidade()).isGreaterThanOrEqualTo(ranking.get(1).probabilidade());
  }
}
