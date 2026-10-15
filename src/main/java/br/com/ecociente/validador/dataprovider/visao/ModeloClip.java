package br.com.ecociente.validador.dataprovider.visao;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * CLIP (ViT-B/32) em ONNX Runtime, só CPU: encoder de imagem e de texto.
 * Os arquivos vêm do export do Hugging Face (vision_model.onnx, text_model.onnx, tokenizer.json) na pasta CLIP_DIR.
 * Não é thread-safe por contrato: quem chama sincroniza (o ClassificadorClipGateway faz isso).
 */
public final class ModeloClip implements AutoCloseable {

  /** Modelo de origem dos pesos: a cabeça treinada guarda este nome e o serviço recusa outro CLIP. */
  public static final String MODELO = "openai/clip-vit-base-patch32";

  private final OrtEnvironment ambiente;
  private final OrtSession sessaoVisao;
  private final OrtSession sessaoTexto;
  private final TokenizadorClip tokenizador;

  public ModeloClip(Path diretorio) throws OrtException, IOException {
    this.ambiente = OrtEnvironment.getEnvironment();
    this.sessaoVisao = ambiente.createSession(diretorio.resolve("vision_model.onnx").toString(), new OrtSession.SessionOptions());
    this.sessaoTexto = ambiente.createSession(diretorio.resolve("text_model.onnx").toString(), new OrtSession.SessionOptions());
    this.tokenizador = new TokenizadorClip(diretorio.resolve("tokenizer.json"));
  }

  /** Embedding da imagem no espaço do CLIP, sem normalizar (entrada da cabeça treinada). */
  public float[] embeddingImagem(BufferedImage imagem) {
    float[] tensor = PreprocessamentoClip.paraTensor(imagem);
    String entrada = sessaoVisao.getInputNames().iterator().next();
    try (OnnxTensor pixels = OnnxTensor.createTensor(
            ambiente, FloatBuffer.wrap(tensor), new long[] {1, 3, PreprocessamentoClip.LADO, PreprocessamentoClip.LADO});
        OrtSession.Result resultado = sessaoVisao.run(Map.of(entrada, pixels))) {
      return primeiroVetor(resultado, "image_embeds");
    } catch (OrtException erro) {
      throw new IllegalStateException("Falha ao rodar o encoder de imagem do CLIP", erro);
    }
  }

  /** Embedding de um prompt de texto (sem normalizar). */
  public float[] embeddingTexto(String texto) {
    long[] ids = java.util.Arrays.stream(tokenizador.codificar(texto)).asLongStream().toArray();
    Set<String> nomes = sessaoTexto.getInputNames();
    Map<String, OnnxTensor> entradas = new HashMap<>();
    try {
      for (String nome : nomes) {
        entradas.put(nome, switch (nome) {
          case "input_ids" -> OnnxTensor.createTensor(ambiente, new long[][] {ids});
          case "attention_mask" -> OnnxTensor.createTensor(ambiente, new long[][] {ones(ids.length)});
          case "position_ids" -> OnnxTensor.createTensor(ambiente, new long[][] {sequencia(ids.length)});
          default -> throw new IllegalStateException("Entrada desconhecida no encoder de texto do CLIP: " + nome);
        });
      }
      try (OrtSession.Result resultado = sessaoTexto.run(entradas)) {
        return primeiroVetor(resultado, "text_embeds");
      }
    } catch (OrtException erro) {
      throw new IllegalStateException("Falha ao rodar o encoder de texto do CLIP", erro);
    } finally {
      entradas.values().forEach(OnnxTensor::close);
    }
  }

  @Override
  public void close() {
    try {
      sessaoVisao.close();
      sessaoTexto.close();
    } catch (OrtException erro) {
      throw new IllegalStateException("Falha ao fechar as sessões do CLIP", erro);
    }
  }

  private static float[] primeiroVetor(OrtSession.Result resultado, String nomeEsperado) throws OrtException {
    float[][] saida = (float[][]) resultado.get(nomeEsperado).orElse(resultado.get(0)).getValue();
    return saida[0];
  }

  private static long[] ones(int n) {
    long[] v = new long[n];
    java.util.Arrays.fill(v, 1L);
    return v;
  }

  private static long[] sequencia(int n) {
    long[] v = new long[n];
    for (int i = 0; i < n; i++) {
      v[i] = i;
    }
    return v;
  }
}
