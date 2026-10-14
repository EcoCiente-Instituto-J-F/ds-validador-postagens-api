package br.com.ecociente.validador.core.imagem;

/**
 * Redimensionamento no mesmo estilo do Pillow (ImagingResample): filtro com suporte escalado pela
 * redução, pesos normalizados e arredondamento a 8 bits em cada passada (horizontal, depois vertical).
 * Usar o mesmo algoritmo mantém o dHash igual ao das postagens já gravadas e o pré-processamento do CLIP
 * igual ao do modelo treinado.
 */
public final class Reamostragem {

  public enum Nucleo {
    /** Pillow LANCZOS (suporte 3): dHash. */
    LANCZOS(3.0),
    /** Pillow BICUBIC (a = -0.5, suporte 2): pré-processamento do CLIP. */
    BICUBICA(2.0);

    private final double suporte;

    Nucleo(double suporte) {
      this.suporte = suporte;
    }

    double suporte() {
      return suporte;
    }

    double peso(double x) {
      x = Math.abs(x);
      if (this == LANCZOS) {
        return x < 3.0 ? sinc(x) * sinc(x / 3.0) : 0.0;
      }
      double a = -0.5;
      if (x < 1.0) {
        return ((a + 2.0) * x - (a + 3.0)) * x * x + 1.0;
      }
      if (x < 2.0) {
        return (((x - 5.0) * x + 8.0) * x - 4.0) * a;
      }
      return 0.0;
    }

    private static double sinc(double x) {
      if (x == 0.0) {
        return 1.0;
      }
      return Math.sin(Math.PI * x) / (Math.PI * x);
    }
  }

  private Reamostragem() {
  }

  /** Redimensiona um canal (valores 0–255, linha a linha) de largura x altura para novaLargura x novaAltura. */
  public static int[] redimensionar(int[] canal, int largura, int altura, int novaLargura, int novaAltura, Nucleo nucleo) {
    int[] temporario = passada(canal, largura, altura, novaLargura, nucleo, true);
    return passada(temporario, novaLargura, altura, novaAltura, nucleo, false);
  }

  /** Uma passada ao longo de um eixo; horizontal = true reduz a largura, senão reduz a altura. */
  private static int[] passada(int[] origem, int largura, int altura, int novaDimensao, Nucleo nucleo, boolean horizontal) {
    int tamanhoEixo = horizontal ? largura : altura;
    Coeficientes coef = coeficientes(tamanhoEixo, novaDimensao, nucleo);
    int novaLargura = horizontal ? novaDimensao : largura;
    int novaAltura = horizontal ? altura : novaDimensao;
    int[] destino = new int[novaLargura * novaAltura];
    for (int y = 0; y < novaAltura; y++) {
      for (int x = 0; x < novaLargura; x++) {
        int i = horizontal ? x : y;
        int inicio = coef.inicio[i];
        double soma = 0;
        for (int k = 0; k < coef.pesos[i].length; k++) {
          int idx = horizontal ? y * largura + (inicio + k) : (inicio + k) * largura + x;
          soma += origem[idx] * coef.pesos[i][k];
        }
        destino[y * novaLargura + x] = (int) Math.min(255, Math.max(0, Math.floor(soma + 0.5)));
      }
    }
    return destino;
  }

  private record Coeficientes(int[] inicio, double[][] pesos) {
  }

  private static Coeficientes coeficientes(int entrada, int saida, Nucleo nucleo) {
    double escala = (double) entrada / saida;
    double escalaFiltro = Math.max(escala, 1.0);
    double suporte = nucleo.suporte() * escalaFiltro;
    int[] inicio = new int[saida];
    double[][] pesos = new double[saida][];
    for (int i = 0; i < saida; i++) {
      double centro = (i + 0.5) * escala;
      int xmin = Math.max((int) (centro - suporte + 0.5), 0);
      int xmax = Math.min((int) (centro + suporte + 0.5), entrada) - xmin;
      double[] w = new double[xmax];
      double total = 0;
      for (int k = 0; k < xmax; k++) {
        w[k] = nucleo.peso((k + xmin - centro + 0.5) / escalaFiltro);
        total += w[k];
      }
      if (total != 0) {
        for (int k = 0; k < xmax; k++) {
          w[k] /= total;
        }
      }
      inicio[i] = xmin;
      pesos[i] = w;
    }
    return new Coeficientes(inicio, pesos);
  }
}
