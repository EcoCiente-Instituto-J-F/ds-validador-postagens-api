package br.com.ecociente.validador.entrypoint.dto.response;

import java.util.List;

import br.com.ecociente.validador.core.domain.Alternativa;
import br.com.ecociente.validador.core.domain.VeredictoFoto;

/** Contrato do POST /triagem (snake_case na serialização). */
public record VeredictoFotoResponse(
    String status,
    Boolean pertinente,
    String categoriaInformada,
    String categoriaDetectada,
    Double confianca,
    String hashFoto,
    List<String> sinais,
    boolean segundaOpiniao,
    List<AlternativaResponse> alternativas,
    String mensagem) {

  public static VeredictoFotoResponse de(VeredictoFoto veredito) {
    return new VeredictoFotoResponse(
        veredito.status().getValor(),
        veredito.pertinente(),
        veredito.categoriaInformada(),
        veredito.categoriaDetectada(),
        veredito.confianca(),
        veredito.hashFoto(),
        veredito.sinais(),
        veredito.segundaOpiniao(),
        veredito.alternativas().stream().map(AlternativaResponse::de).toList(),
        veredito.mensagem());
  }

  public record AlternativaResponse(String categoria, double confianca) {

    static AlternativaResponse de(Alternativa alternativa) {
      return new AlternativaResponse(alternativa.categoria(), alternativa.confianca());
    }
  }
}
