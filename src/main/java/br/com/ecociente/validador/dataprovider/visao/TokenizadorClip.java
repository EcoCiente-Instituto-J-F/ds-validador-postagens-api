package br.com.ecociente.validador.dataprovider.visao;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tokenizador BPE do CLIP (byte-level), lido do tokenizer.json do Hugging Face.
 * Os prompts das categorias são texto simples em inglês: o tokenizador cobre o caso com fidelidade ao original.
 */
public final class TokenizadorClip {

  static final int INICIO = 49406; // <|startoftext|>
  static final int FIM = 49407; // <|endoftext|>

  private static final Pattern PADRAO = Pattern.compile(
      "<\\|startoftext\\|>|<\\|endoftext\\|>|'s|'t|'re|'ve|'m|'ll|'d|[\\p{L}]+|[\\p{N}]|[^\\s\\p{L}\\p{N}]+",
      Pattern.CASE_INSENSITIVE);
  private static final Map<Integer, Character> BYTE_PARA_CHAR = mapaDeBytes();

  private final Map<String, Integer> vocabulario;
  private final Map<String, Integer> ranks = new HashMap<>();
  private final Map<String, List<String>> cache = new ConcurrentHashMap<>();

  public TokenizadorClip(Path tokenizerJson) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode modelo = mapper.readTree(Files.readString(tokenizerJson, StandardCharsets.UTF_8)).path("model");
    this.vocabulario = mapper.convertValue(modelo.path("vocab"), new TypeReference<Map<String, Integer>>() { });
    int rank = 0;
    for (Iterator<JsonNode> it = modelo.path("merges").elements(); it.hasNext(); rank++) {
      JsonNode merge = it.next();
      // formato antigo: "a b" como texto; formato novo: ["a", "b"]
      String par = merge.isArray() ? merge.get(0).asText() + " " + merge.get(1).asText() : merge.asText();
      ranks.put(par, rank);
    }
  }

  /** Ids do texto com <|startoftext|> e <|endoftext|>, sem padding. */
  public int[] codificar(String texto) {
    String limpo = texto.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    List<Integer> ids = new ArrayList<>();
    ids.add(INICIO);
    Matcher m = PADRAO.matcher(limpo);
    while (m.find()) {
      StringBuilder bytesComoChars = new StringBuilder();
      for (byte b : m.group().getBytes(StandardCharsets.UTF_8)) {
        bytesComoChars.append(BYTE_PARA_CHAR.get(b & 0xFF));
      }
      for (String pedaco : bpe(bytesComoChars.toString())) {
        Integer id = vocabulario.get(pedaco);
        if (id != null) {
          ids.add(id);
        }
      }
    }
    ids.add(FIM);
    return ids.stream().mapToInt(Integer::intValue).toArray();
  }

  private List<String> bpe(String token) {
    List<String> emCache = cache.get(token);
    if (emCache != null) {
      return emCache;
    }
    List<String> palavra = new ArrayList<>();
    for (int i = 0; i < token.length() - 1; i++) {
      palavra.add(String.valueOf(token.charAt(i)));
    }
    palavra.add(token.charAt(token.length() - 1) + "</w>");

    while (palavra.size() > 1) {
      int melhor = Integer.MAX_VALUE;
      int posicao = -1;
      for (int i = 0; i < palavra.size() - 1; i++) {
        Integer rank = ranks.get(palavra.get(i) + " " + palavra.get(i + 1));
        if (rank != null && rank < melhor) {
          melhor = rank;
          posicao = i;
        }
      }
      if (posicao < 0) {
        break;
      }
      String primeira = palavra.get(posicao);
      String segunda = palavra.get(posicao + 1);
      List<String> nova = new ArrayList<>();
      for (int i = 0; i < palavra.size(); i++) {
        if (i < palavra.size() - 1 && palavra.get(i).equals(primeira) && palavra.get(i + 1).equals(segunda)) {
          nova.add(primeira + segunda);
          i++;
        } else {
          nova.add(palavra.get(i));
        }
      }
      palavra = nova;
    }
    cache.put(token, palavra);
    return palavra;
  }

  /** bytes_to_unicode do GPT-2: cada byte vira um caractere imprimível, para o BPE não ver espaços nem controles. */
  private static Map<Integer, Character> mapaDeBytes() {
    List<Integer> bs = new ArrayList<>();
    for (int b = '!'; b <= '~'; b++) {
      bs.add(b);
    }
    for (int b = 0xA1; b <= 0xAC; b++) {
      bs.add(b);
    }
    for (int b = 0xAE; b <= 0xFF; b++) {
      bs.add(b);
    }
    List<Integer> cs = new ArrayList<>(bs);
    int n = 0;
    for (int b = 0; b < 256; b++) {
      if (!bs.contains(b)) {
        bs.add(b);
        cs.add(256 + n);
        n++;
      }
    }
    Map<Integer, Character> mapa = new HashMap<>();
    for (int i = 0; i < bs.size(); i++) {
      mapa.put(bs.get(i), (char) cs.get(i).intValue());
    }
    return Map.copyOf(mapa);
  }
}
