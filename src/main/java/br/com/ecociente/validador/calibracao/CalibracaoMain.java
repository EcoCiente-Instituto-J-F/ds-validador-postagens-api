package br.com.ecociente.validador.calibracao;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import br.com.ecociente.validador.core.domain.Categorias;
import br.com.ecociente.validador.core.domain.Classe;
import br.com.ecociente.validador.core.domain.ClasseProbabilidade;
import br.com.ecociente.validador.core.exception.FotoException;
import br.com.ecociente.validador.dataprovider.foto.DecodificadorFoto;
import br.com.ecociente.validador.dataprovider.visao.ClassificadorClipGateway;
import br.com.ecociente.validador.dataprovider.visao.ModeloClip;

/**
 * Mede o classificador em fotos reais para escolher o limiar_confianca.
 * Uso: ./gradlew calibrar -PpastaAmostras=amostras   (com CLIP_DIR apontando para os arquivos ONNX)
 * Pastas: amostras/<slug da categoria>/*.jpg  e  amostras/nao_residuo/*.jpg (selfies, pets, prints...)
 */
public final class CalibracaoMain {

  static final double[] LIMIARES = {0.3, 0.4, 0.5, 0.6, 0.7, 0.8};

  private CalibracaoMain() {
  }

  public static void main(String[] args) throws Exception {
    Path pasta = Path.of(args.length > 0 ? args[0] : "amostras");
    Path clipDir = Path.of(System.getenv().getOrDefault("CLIP_DIR", "modelos/clip-vit-base-patch32"));
    List<CalibracaoMetricas.Resultado> resultados = new ArrayList<>();
    try (ModeloClip modelo = new ModeloClip(clipDir)) {
      ClassificadorClipGateway classificador = new ClassificadorClipGateway(modelo);
      for (Amostras.Foto foto : Amostras.listar(pasta)) {
        BufferedImage imagem = carregar(foto.arquivo());
        if (imagem == null) {
          continue;
        }
        ClasseProbabilidade topo = classificador.ranking(imagem).get(0);
        resultados.add(new CalibracaoMetricas.Resultado(foto.rotulo(), topo.classe(), topo.probabilidade()));
        String marca = topo.classe().equals(foto.rotulo()) ? "ok " : "ERR";
        System.out.printf("%s %s: %s (%.0f%%)%n", marca, foto.arquivo(), topo.classe(), topo.probabilidade() * 100);
      }
    }
    if (resultados.isEmpty()) {
      throw new IllegalStateException("Nenhuma foto válida em " + pasta + "/<categoria>/");
    }
    imprimirRelatorio(resultados);
  }

  private static BufferedImage carregar(Path arquivo) throws IOException {
    try {
      return DecodificadorFoto.decodificar(Files.readAllBytes(arquivo)).imagem();
    } catch (FotoException erro) {
      System.out.println("--- " + arquivo + ": pulada (" + erro.getMessage() + ")");
      return null;
    }
  }

  private static void imprimirRelatorio(List<CalibracaoMetricas.Resultado> resultados) {
    long acertos = resultados.stream().filter(r -> r.real().equals(r.detectada())).count();
    System.out.printf("%n%d fotos | acurácia: %.1f%%%n%n", resultados.size(), 100.0 * acertos / resultados.size());
    System.out.println("limiar | aprova_corretas | aprovaria_errada");
    for (double limiar : LIMIARES) {
      double[] taxas = CalibracaoMetricas.taxas(resultados, limiar);
      System.out.printf("  %.1f  |     %5.1f%%      |     %5.1f%%%n", limiar, taxas[0], taxas[1]);
    }
    System.out.println("\nPor categoria (para limiares-por-categoria): limiar -> aprova_corretas / aprovaria_errada");
    for (Classe categoria : Categorias.CATEGORIAS) {
      StringBuilder linha = new StringBuilder(String.format("  %-9s", categoria.slug()));
      for (double limiar : LIMIARES) {
        double[] t = CalibracaoMetricas.taxasDaCategoria(resultados, categoria.slug(), limiar);
        linha.append(String.format("  %.1f: %.0f%%/%.0f%%", limiar, t[0], t[1]));
      }
      System.out.println(linha);
    }
  }
}
