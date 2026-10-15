package br.com.ecociente.validador.core.domain;

import java.util.List;

/** Veredito da triagem automática da foto, ou falha tipada quando a foto não pôde ser analisada. */
public record VeredictoFoto(
    StatusTriagem status,
    Boolean pertinente,
    String categoriaInformada,
    String categoriaDetectada,
    Double confianca,
    String hashFoto,
    List<String> sinais,
    List<Alternativa> alternativas,
    String mensagem) {

  /**
   * Resultado sem passar pelo modelo: arquivo inválido (status OK = veredito False) ou falha tipada (sem veredito).
   */
  public static VeredictoFoto rejeitada(String categoriaInformada, String motivo, StatusTriagem status) {
    Boolean pertinente = status == StatusTriagem.OK ? Boolean.FALSE : null;
    return new VeredictoFoto(status, pertinente, categoriaInformada, null, null, null, List.of(), List.of(), motivo);
  }

  public VeredictoFoto comSinais(String hashFoto, List<String> sinais) {
    return new VeredictoFoto(status, pertinente, categoriaInformada, categoriaDetectada, confianca, hashFoto,
        List.copyOf(sinais), alternativas, mensagem);
  }
}
