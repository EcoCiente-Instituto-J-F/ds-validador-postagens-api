package br.com.ecociente.validador.dataprovaider.visao;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CabecaTreinadaTest {

  @TempDir
  Path pasta;

  @Test
  void aprendeClassesSeparaveis() {
    Random sorteio = new Random(42);
    List<double[]> embeddings = new ArrayList<>();
    List<String> rotulos = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      boolean vidro = i % 2 == 0;
      double[] vetor = new double[8];
      for (int j = 0; j < vetor.length; j++) {
        vetor[j] = (vidro ? 1.0 : -1.0) + sorteio.nextGaussian() * 0.1;
      }
      embeddings.add(vetor);
      rotulos.add(vidro ? "vidro" : "metal");
    }

    CabecaTreinada cabeca = CabecaTreinada.treinar(embeddings.toArray(new double[0][]), rotulos, "modelo-x");

    float[] parecidoComVidro = new float[8];
    java.util.Arrays.fill(parecidoComVidro, 1f);
    assertThat(cabeca.ranking(parecidoComVidro).get(0).classe()).isEqualTo("vidro");
  }

  @Test
  void salvarECarregarPreservaAsProbabilidades() throws Exception {
    double[][] embeddings = {{1, 0}, {0, 1}, {1, 1}, {-1, -1}};
    List<String> rotulos = List.of("vidro", "metal", "vidro", "metal");
    CabecaTreinada original = CabecaTreinada.treinar(embeddings, rotulos, "modelo-x");
    Path arquivo = pasta.resolve("cabeca.json");

    original.salvar(arquivo);
    CabecaTreinada carregada = CabecaTreinada.carregar(arquivo);

    float[] teste = {0.5f, 0.2f};
    assertThat(carregada.modelo()).isEqualTo("modelo-x");
    assertThat(carregada.ranking(teste).get(0).probabilidade())
        .isEqualTo(original.ranking(teste).get(0).probabilidade(), org.assertj.core.data.Offset.offset(1e-9));
  }

  @Test
  void treinoExigeDuasClasses() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            CabecaTreinada.treinar(new double[][] {{1}, {2}}, List.of("vidro", "vidro"), "m"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
