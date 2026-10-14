package br.com.ecociente.validador.calibracao;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class CalibracaoMetricasTest {

  private final List<CalibracaoMetricas.Resultado> resultados = List.of(
      new CalibracaoMetricas.Resultado("vidro", "vidro", 0.9),   // acerto forte
      new CalibracaoMetricas.Resultado("vidro", "metal", 0.6),   // errou, com confiança
      new CalibracaoMetricas.Resultado("metal", "metal", 0.3),   // acerto fraco
      new CalibracaoMetricas.Resultado("nao_residuo", "vidro", 0.7)); // foto que não é resíduo, aprovada como vidro

  @Test
  void taxasDePoliticaDeAprovacao() {
    double[] taxas = CalibracaoMetricas.taxas(resultados, 0.5);

    assertThat(taxas[0]).isEqualTo(100.0 / 3, org(0.01));   // 1 de 3 resíduos aprovados na categoria certa
    assertThat(taxas[1]).isEqualTo(50.0, org(0.01));        // 2 de 4 fotos aprovadas como outra categoria
  }

  @Test
  void taxasDaCategoriaOlhamSoAquelaClasse() {
    double[] vidro = CalibracaoMetricas.taxasDaCategoria(resultados, "vidro", 0.5);

    assertThat(vidro[0]).isEqualTo(50.0, org(0.01)); // 1 de 2 fotos de vidro aprovadas como vidro
    assertThat(vidro[1]).isEqualTo(50.0, org(0.01)); // 1 de 2 fotos de fora passou como vidro
  }

  private static org.assertj.core.data.Offset<Double> org(double valor) {
    return org.assertj.core.data.Offset.offset(valor);
  }
}
