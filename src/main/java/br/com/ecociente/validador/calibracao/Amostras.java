package br.com.ecociente.validador.calibracao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** Lê as fotos de calibração: pasta/<categoria>/*.jpg (a pasta é o rótulo real). */
final class Amostras {

  static final Set<String> EXTENSOES = Set.of(".jpg", ".jpeg", ".png", ".webp");

  record Foto(String rotulo, Path arquivo) {
  }

  private Amostras() {
  }

  static List<Foto> listar(Path pasta) throws IOException {
    List<Foto> fotos = new ArrayList<>();
    try (Stream<Path> pastas = Files.list(pasta)) {
      for (Path categoria : pastas.filter(Files::isDirectory).sorted().toList()) {
        try (Stream<Path> arquivos = Files.list(categoria)) {
          arquivos.filter(Amostras::ehFoto).sorted()
              .forEach(arquivo -> fotos.add(new Foto(categoria.getFileName().toString(), arquivo)));
        }
      }
    }
    return fotos;
  }

  private static boolean ehFoto(Path arquivo) {
    String nome = arquivo.getFileName().toString().toLowerCase(Locale.ROOT);
    return EXTENSOES.stream().anyMatch(nome::endsWith);
  }
}
