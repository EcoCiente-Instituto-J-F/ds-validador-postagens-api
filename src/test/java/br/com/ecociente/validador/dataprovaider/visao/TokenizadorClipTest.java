package br.com.ecociente.validador.dataprovaider.visao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Ids de referência gerados pelo CLIPTokenizerFast do transformers com o mesmo tokenizer.json. */
@Tag("modelo")
class TokenizadorClipTest {

  private static final Path CLIP_DIR = Path.of(System.getenv().getOrDefault("CLIP_DIR", "modelos/clip-vit-base-patch32"));

  @Test
  void idsIguaisAoTokenizadorDoHuggingFace() throws Exception {
    Path tokenizer = CLIP_DIR.resolve("tokenizer.json");
    assumeTrue(Files.exists(tokenizer), "precisa do tokenizer.json do CLIP em CLIP_DIR");
    var tokenizador = new TokenizadorClip(tokenizer);

    assertThat(tokenizador.codificar("a photo of a plastic bottle"))
        .containsExactly(49406, 320, 1125, 539, 320, 6102, 5392, 49407);
    assertThat(tokenizador.codificar("a selfie")).containsExactly(49406, 320, 3666, 49407);
    assertThat(tokenizador.codificar("a photo of coffee grounds and eggshells"))
        .containsExactly(49406, 320, 1125, 539, 2453, 8454, 537, 13778, 19265, 49407);
  }

  @Test
  void normalizaCaixaEEspacos() throws Exception {
    Path tokenizer = CLIP_DIR.resolve("tokenizer.json");
    assumeTrue(Files.exists(tokenizer), "precisa do tokenizer.json do CLIP em CLIP_DIR");
    var tokenizador = new TokenizadorClip(tokenizer);

    assertThat(tokenizador.codificar("A Photo of   a GLASS jar!"))
        .containsExactly(49406, 320, 1125, 539, 320, 3313, 10837, 256, 49407);
  }
}
