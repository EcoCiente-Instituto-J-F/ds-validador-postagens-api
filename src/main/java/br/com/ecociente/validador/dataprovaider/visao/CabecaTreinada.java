package br.com.ecociente.validador.dataprovaider.visao;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;

import br.com.ecociente.validador.core.domain.Categorias;
import br.com.ecociente.validador.core.domain.ClasseProbabilidade;
import br.com.ecociente.validador.core.gateway.ClassificadorGateway;

/**
 * Cabeça treinada: regressão logística sobre os embeddings do CLIP, treinada com as fotos rotuladas do app.
 * Em geral supera o zero-shot, mas só vale com dados reais: treine com ./gradlew treinarCabeca.
 * Guardada em JSON (antes era .npz do numpy): em produção é só uma multiplicação de matrizes.
 */
public final class CabecaTreinada {

  private final List<String> classes;
  private final double[][] pesos;
  private final double[] vies;
  private final double[] media;
  private final double[] desvio;
  private final String modelo;

  public CabecaTreinada(List<String> classes, double[][] pesos, double[] vies, double[] media, double[] desvio, String modelo) {
    this.classes = List.copyOf(classes);
    this.pesos = pesos;
    this.vies = vies;
    this.media = media;
    this.desvio = desvio;
    this.modelo = modelo; // CLIP que gerou os embeddings: com outro modelo as probabilidades não fazem sentido
  }

  public String modelo() {
    return modelo;
  }

  public List<ClasseProbabilidade> ranking(float[] vetor) {
    int c = classes.size();
    double[] logits = new double[c];
    for (int k = 0; k < c; k++) {
      double soma = vies[k];
      for (int j = 0; j < vetor.length; j++) {
        soma += pesos[k][j] * ((vetor[j] - media[j]) / desvio[j]);
      }
      logits[k] = soma;
    }
    double[] probabilidades = softmax(logits);
    List<ClasseProbabilidade> ranking = new ArrayList<>();
    for (int k = 0; k < c; k++) {
      ranking.add(new ClasseProbabilidade(classes.get(k), probabilidades[k]));
    }
    ranking.sort((a, b) -> Double.compare(b.probabilidade(), a.probabilidade()));
    return ranking;
  }

  public void salvar(Path caminho) throws IOException {
    Map<String, Object> conteudo = Map.of(
        "classes", classes, "pesos", pesos, "vies", vies, "media", media, "desvio", desvio, "modelo", modelo);
    new ObjectMapper().writeValue(caminho.toFile(), conteudo);
  }

  @SuppressWarnings("unchecked")
  public static CabecaTreinada carregar(Path caminho) throws IOException {
    Map<String, Object> conteudo = new ObjectMapper().readValue(caminho.toFile(), Map.class);
    List<String> classes = (List<String>) conteudo.get("classes");
    Set<String> desconhecidas = new TreeSet<>(classes);
    desconhecidas.removeAll(Categorias.TODAS_AS_CLASSES.stream().map(c -> c.slug()).toList());
    if (!desconhecidas.isEmpty()) {
      throw new IllegalArgumentException("A cabeça tem classes fora do catálogo: " + String.join(", ", desconhecidas) + ".");
    }
    return new CabecaTreinada(
        classes,
        new ObjectMapper().convertValue(conteudo.get("pesos"), double[][].class),
        new ObjectMapper().convertValue(conteudo.get("vies"), double[].class),
        new ObjectMapper().convertValue(conteudo.get("media"), double[].class),
        new ObjectMapper().convertValue(conteudo.get("desvio"), double[].class),
        (String) conteudo.get("modelo"));
  }

  /**
   * Regressão logística multinomial por gradiente em lote, com L2 (poucas fotos para muitas dimensões).
   */
  public static CabecaTreinada treinar(double[][] embeddings, List<String> rotulos, String modelo) {
    return treinar(embeddings, rotulos, modelo, 300, 0.1, 1e-2);
  }

  public static CabecaTreinada treinar(
      double[][] embeddings, List<String> rotulos, String modelo, int passos, double taxa, double l2) {
    List<String> classes = rotulos.stream().distinct().sorted().toList();
    if (classes.size() < 2) {
      throw new IllegalArgumentException("O treino precisa de pelo menos duas classes.");
    }
    int n = embeddings.length;
    int d = embeddings[0].length;
    double[] media = new double[d];
    double[] desvio = new double[d];
    for (double[] e : embeddings) {
      for (int j = 0; j < d; j++) {
        media[j] += e[j] / n;
      }
    }
    for (double[] e : embeddings) {
      for (int j = 0; j < d; j++) {
        desvio[j] += (e[j] - media[j]) * (e[j] - media[j]) / n;
      }
    }
    for (int j = 0; j < d; j++) {
      desvio[j] = Math.sqrt(desvio[j]) + 1e-6;
    }
    double[][] x = new double[n][d];
    for (int i = 0; i < n; i++) {
      for (int j = 0; j < d; j++) {
        x[i][j] = (embeddings[i][j] - media[j]) / desvio[j];
      }
    }
    int c = classes.size();
    double[][] alvo = new double[n][c];
    for (int i = 0; i < n; i++) {
      alvo[i][classes.indexOf(rotulos.get(i))] = 1;
    }
    double[][] pesos = new double[c][d];
    double[] vies = new double[c];
    for (int passo = 0; passo < passos; passo++) {
      double[][] erro = new double[n][c];
      for (int i = 0; i < n; i++) {
        double[] logits = new double[c];
        for (int k = 0; k < c; k++) {
          double soma = vies[k];
          for (int j = 0; j < d; j++) {
            soma += pesos[k][j] * x[i][j];
          }
          logits[k] = soma;
        }
        double[] probabilidades = softmax(logits);
        for (int k = 0; k < c; k++) {
          erro[i][k] = (probabilidades[k] - alvo[i][k]) / n;
        }
      }
      for (int k = 0; k < c; k++) {
        for (int j = 0; j < d; j++) {
          double gradiente = 0;
          for (int i = 0; i < n; i++) {
            gradiente += erro[i][k] * x[i][j];
          }
          pesos[k][j] -= taxa * (gradiente + l2 * pesos[k][j]);
        }
        double gradienteVies = 0;
        for (int i = 0; i < n; i++) {
          gradienteVies += erro[i][k];
        }
        vies[k] -= taxa * gradienteVies;
      }
    }
    return new CabecaTreinada(classes, pesos, vies, media, desvio, modelo);
  }

  static double[] softmax(double[] logits) {
    double maximo = Double.NEGATIVE_INFINITY;
    for (double v : logits) {
      maximo = Math.max(maximo, v);
    }
    double soma = 0;
    double[] saida = new double[logits.length];
    for (int i = 0; i < logits.length; i++) {
      saida[i] = Math.exp(logits[i] - maximo);
      soma += saida[i];
    }
    for (int i = 0; i < saida.length; i++) {
      saida[i] /= soma;
    }
    return saida;
  }
}
