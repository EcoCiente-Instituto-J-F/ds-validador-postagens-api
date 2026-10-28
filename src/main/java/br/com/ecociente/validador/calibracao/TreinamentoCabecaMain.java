package br.com.ecociente.validador.calibracao;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import br.com.ecociente.validador.core.domain.Categorias;
import br.com.ecociente.validador.core.domain.ClasseProbabilidade;
import br.com.ecociente.validador.core.exception.FotoException;
import br.com.ecociente.validador.dataprovider.foto.DecodificadorFoto;
import br.com.ecociente.validador.dataprovider.visao.CabecaTreinada;
import br.com.ecociente.validador.dataprovider.visao.ClassificadorClipGateway;
import br.com.ecociente.validador.dataprovider.visao.ModeloClip;

/**
 * Treina a cabeça sobre os embeddings do CLIP e diz se ela vence o zero-shot, na mesma fatia de teste.
 * Uso: ./gradlew treinarCabeca -PpastaAmostras=amostras -PsaidaCabeca=cabeca.json
 * Só ligue em produção (CABECA_TREINADA=cabeca.json) se a acurácia da cabeça passar a do zero-shot com folga.
 */
public final class TreinamentoCabecaMain {

  static final int MIN_FOTOS = 20;

  private TreinamentoCabecaMain() {
  }

  public static void main(String[] args) throws Exception {
    Path pasta = Path.of(args[0]);
    Path saida = Path.of(args[1]);
    Path clipDir = Path.of(System.getenv().getOrDefault("CLIP_DIR", "modelos/clip-vit-base-patch32"));

    List<String> rotulos = new ArrayList<>();
    List<double[]> embeddings = new ArrayList<>();
    List<String> zeroShot = new ArrayList<>();
    try (ModeloClip modelo = new ModeloClip(clipDir)) {
      ClassificadorClipGateway classificador = new ClassificadorClipGateway(modelo);
      for (Amostras.Foto foto : Amostras.listar(pasta)) {
        BufferedImage imagem = carregar(foto.arquivo());
        if (imagem == null) {
          continue;
        }
        rotulos.add(foto.rotulo());
        float[] vetor = modelo.embeddingImagem(imagem);
        double[] convertido = new double[vetor.length];
        for (int i = 0; i < vetor.length; i++) {
          convertido[i] = vetor[i];
        }
        embeddings.add(convertido);
        zeroShot.add(classificador.ranking(imagem).get(0).classe());
      }
    }
    if (rotulos.isEmpty()) {
      throw new IllegalStateException("Nenhuma foto válida em " + pasta + "/<categoria>/");
    }
    validarRotulos(rotulos);
    if (rotulos.size() < MIN_FOTOS) {
      throw new IllegalStateException("Poucas fotos para treinar: " + rotulos.size() + " (mínimo " + MIN_FOTOS + ").");
    }

    double[][] matriz = embeddings.toArray(new double[0][]);
    List<Integer> teste = new ArrayList<>();
    List<Integer> treino = new ArrayList<>();
    for (int i = 0; i < rotulos.size(); i++) {
      (i % 5 == 0 ? teste : treino).add(i); // 1 em cada 5 fica de fora, em todas as classes
    }
    CabecaTreinada cabecaTeste = CabecaTreinada.treinar(
        linhas(matriz, treino), rotulosDe(rotulos, treino), ModeloClip.MODELO);
    long acertosCabeca = teste.stream()
        .filter(i -> cabecaTeste.ranking(toFloat(matriz[i])).get(0).classe().equals(rotulos.get(i)))
        .count();
    long acertosZeroShot = teste.stream().filter(i -> zeroShot.get(i).equals(rotulos.get(i))).count();

    System.out.printf("%d fotos; teste com %d%n", rotulos.size(), teste.size());
    System.out.printf("acurácia no teste: cabeça %.1f%% | zero-shot %.1f%%%n",
        100.0 * acertosCabeca / teste.size(), 100.0 * acertosZeroShot / teste.size());

    CabecaTreinada cabecaFinal = CabecaTreinada.treinar(matriz, rotulos, ModeloClip.MODELO);
    cabecaFinal.salvar(saida);
    System.out.println("cabeça final (todas as fotos) salva em " + saida);
  }

  private static void validarRotulos(List<String> rotulos) {
    for (String rotulo : rotulos) {
      if (!rotulo.equals("nao_residuo") && Categorias.porSlug(rotulo).isEmpty()) {
        throw new IllegalArgumentException("Pastas fora do catálogo: " + rotulo + ".");
      }
    }
  }

  private static double[][] linhas(double[][] matriz, List<Integer> indices) {
    return indices.stream().map(i -> matriz[i]).toArray(double[][]::new);
  }

  private static List<String> rotulosDe(List<String> rotulos, List<Integer> indices) {
    return indices.stream().map(rotulos::get).toList();
  }

  private static float[] toFloat(double[] v) {
    float[] f = new float[v.length];
    for (int i = 0; i < v.length; i++) {
      f[i] = (float) v[i];
    }
    return f;
  }

  private static BufferedImage carregar(Path arquivo) throws IOException {
    try {
      return DecodificadorFoto.decodificar(Files.readAllBytes(arquivo)).imagem();
    } catch (FotoException erro) {
      System.out.println("--- " + arquivo + ": pulada (" + erro.getMessage() + ")");
      return null;
    }
  }
}
