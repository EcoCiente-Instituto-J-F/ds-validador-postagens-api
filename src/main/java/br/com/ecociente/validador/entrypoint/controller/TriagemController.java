package br.com.ecociente.validador.entrypoint.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.ecociente.validador.core.domain.StatusTriagem;
import br.com.ecociente.validador.core.domain.VeredictoFoto;
import br.com.ecociente.validador.core.exception.FotoRecusadaException;
import br.com.ecociente.validador.core.exception.ServicoOcupadoException;
import br.com.ecociente.validador.core.service.TriagemPostagemService;
import br.com.ecociente.validador.entrypoint.dto.response.VeredictoFotoResponse;
import br.com.ecociente.validador.entrypoint.metricas.MetricasTriagem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/v1/postagens")
@Validated
public class TriagemController {

  /** Um JSON por linha, sem a URL (pode ter token de URL assinada): vira o dataset de calibração e feedback. */
  private static final Logger registroDeVereditos = LoggerFactory.getLogger("validador_fotos.veredito");
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_ID_INT4 = 2_147_483_647; // ids do banco são int4

  private final TriagemPostagemService triagem;
  private final MetricasTriagem metricas;

  public TriagemController(TriagemPostagemService triagem, MetricasTriagem metricas) {
    this.triagem = triagem;
    this.metricas = metricas;
  }

  @PostMapping("/{postagemId}/triagem")
  public ResponseEntity<VeredictoFotoResponse> triar(
      @PathVariable @Min(1) @Max(MAX_ID_INT4) int postagemId) {
    long inicio = System.nanoTime();
    VeredictoFoto veredito;
    try {
      veredito = triagem.triar(postagemId);
    } catch (ServicoOcupadoException erro) {
      metricas.pedidoRejeitado();
      throw erro;
    }
    metricas.registrar(veredito, (System.nanoTime() - inicio) / 1e9);
    registrarVeredito(postagemId, veredito);

    if (veredito.status() != StatusTriagem.OK) {
      throw new FotoRecusadaException(veredito.status(), veredito.mensagem());
    }
    return ResponseEntity.status(HttpStatus.OK).body(VeredictoFotoResponse.de(veredito));
  }

  private static void registrarVeredito(int postagemId, VeredictoFoto veredito) {
    Map<String, Object> linha = new LinkedHashMap<>();
    linha.put("id_postagem", postagemId);
    linha.put("status", veredito.status().getValor());
    linha.put("pertinente", veredito.pertinente());
    linha.put("categoria_informada", veredito.categoriaInformada());
    linha.put("categoria_detectada", veredito.categoriaDetectada());
    linha.put("confianca", veredito.confianca());
    linha.put("hash_foto", veredito.hashFoto());
    linha.put("sinais", veredito.sinais());
    linha.put("segunda_opiniao", veredito.segundaOpiniao());
    linha.put("alternativas", VeredictoFotoResponse.de(veredito).alternativas());
    try {
      registroDeVereditos.info(MAPPER.writeValueAsString(linha));
    } catch (JsonProcessingException erro) { // o mapa só tem tipos simples: não deveria falhar
      registroDeVereditos.warn("Veredito da postagem {} não serializado", postagemId, erro);
    }
  }
}
