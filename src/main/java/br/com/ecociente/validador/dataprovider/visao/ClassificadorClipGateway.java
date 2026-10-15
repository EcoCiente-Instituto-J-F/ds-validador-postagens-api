package br.com.ecociente.validador.dataprovider.visao;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import br.com.ecociente.validador.core.domain.Categorias;
import br.com.ecociente.validador.core.domain.Classe;
import br.com.ecociente.validador.core.domain.ClasseProbabilidade;
import br.com.ecociente.validador.core.gateway.ClassificadorGateway;

/**
 * Zero-shot com CLIP: compara a foto com as descrições de cada classe, sem dataset nem treino.
 * Cada prompt recebe uma probabilidade (softmax sobre todos); a probabilidade da classe é a soma dos seus prompts.
 */
public final class ClassificadorClipGateway implements ClassificadorGateway {

  /** logit_scale treinado do CLIP (exp(4.6052) ≈ 100). */
  static final double ESCALA_LOGITS = 100.0;

  private final ModeloClip modelo;
  private final List<String> classeDoPrompt = new ArrayList<>();
  private final List<float[]> textos = new ArrayList<>();

  public ClassificadorClipGateway(ModeloClip modelo) {
    this.modelo = modelo;
    for (Classe classe : Categorias.TODAS_AS_CLASSES) {
      for (String prompt : classe.prompts()) {
        classeDoPrompt.add(classe.slug());
        textos.add(normalizar(modelo.embeddingTexto(prompt)));
      }
    }
  }

  @Override
  public synchronized List<ClasseProbabilidade> ranking(BufferedImage imagem) {
    float[] imagemNormalizada = normalizar(modelo.embeddingImagem(imagem));
    double[] logits = new double[textos.size()];
    double maximo = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < textos.size(); i++) {
      logits[i] = ESCALA_LOGITS * produtoInterno(imagemNormalizada, textos.get(i));
      maximo = Math.max(maximo, logits[i]);
    }
    double soma = 0;
    for (int i = 0; i < logits.length; i++) {
      logits[i] = Math.exp(logits[i] - maximo);
      soma += logits[i];
    }
    Map<String, Double> totais = new LinkedHashMap<>();
    for (int i = 0; i < logits.length; i++) {
      totais.merge(classeDoPrompt.get(i), logits[i] / soma, Double::sum);
    }
    return totais.entrySet().stream()
        .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
        .map(e -> new ClasseProbabilidade(e.getKey(), e.getValue()))
        .toList();
  }

  static float[] normalizar(float[] vetor) {
    double norma = 0;
    for (float v : vetor) {
      norma += v * v;
    }
    norma = Math.sqrt(norma);
    float[] saida = new float[vetor.length];
    for (int i = 0; i < vetor.length; i++) {
      saida[i] = (float) (vetor[i] / norma);
    }
    return saida;
  }

  private static double produtoInterno(float[] a, float[] b) {
    double soma = 0;
    for (int i = 0; i < a.length; i++) {
      soma += a[i] * b[i];
    }
    return soma;
  }
}
