package br.com.ecociente.validador.calibracao;

import java.util.List;

import br.com.ecociente.validador.core.domain.Categorias;

/** Métricas da calibração: não dependem do modelo, então testam sem carregar o CLIP. */
public final class CalibracaoMetricas {

  /** Foto real: classe certa (rótulo da pasta) e o que o modelo viu com a probabilidade. */
  public record Resultado(String real, String detectada, double probabilidade) {
  }

  private CalibracaoMetricas() {
  }

  /**
   * Devolve [aprova_corretas, aprovaria_errada], em %:
   * aprova_corretas: fotos de resíduo aprovadas na categoria certa (quanto maior, melhor);
   * aprovaria_errada: fotos que passariam como OUTRA categoria com confiança >= limiar (quanto menor, melhor).
   */
  public static double[] taxas(List<Resultado> resultados, double limiar) {
    long residuos = resultados.stream().filter(r -> Categorias.porSlug(r.real()).isPresent()).count();
    long corretas = resultados.stream()
        .filter(r -> Categorias.porSlug(r.real()).isPresent())
        .filter(r -> r.detectada().equals(r.real()) && r.probabilidade() >= limiar)
        .count();
    long erradas = resultados.stream()
        .filter(r -> Categorias.porSlug(r.detectada()).isPresent() && !r.detectada().equals(r.real()))
        .filter(r -> r.probabilidade() >= limiar)
        .count();
    return new double[] {
        100.0 * corretas / Math.max(residuos, 1),
        100.0 * erradas / Math.max(resultados.size(), 1)
    };
  }

  /** Mesma ideia de taxas(), olhando só uma categoria: serve para escolher limiares_por_categoria. */
  public static double[] taxasDaCategoria(List<Resultado> resultados, String categoria, double limiar) {
    long dela = resultados.stream().filter(r -> r.real().equals(categoria)).count();
    long deFora = resultados.size() - dela;
    long corretas = resultados.stream()
        .filter(r -> r.real().equals(categoria) && r.detectada().equals(categoria) && r.probabilidade() >= limiar)
        .count();
    long erradas = resultados.stream()
        .filter(r -> !r.real().equals(categoria) && r.detectada().equals(categoria) && r.probabilidade() >= limiar)
        .count();
    return new double[] {100.0 * corretas / Math.max(dela, 1), 100.0 * erradas / Math.max(deFora, 1)};
  }
}
