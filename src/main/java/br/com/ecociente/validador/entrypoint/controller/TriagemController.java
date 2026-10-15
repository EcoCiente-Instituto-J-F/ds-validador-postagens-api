package br.com.ecociente.validador.entrypoint.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.ecociente.validador.core.domain.StatusTriagem;
import br.com.ecociente.validador.core.domain.VeredictoFoto;
import br.com.ecociente.validador.core.exception.FotoException;
import br.com.ecociente.validador.core.exception.ServicoOcupadoException;
import br.com.ecociente.validador.core.service.TriagemPostagemService;
import br.com.ecociente.validador.entrypoint.metricas.MetricasTriagem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/v1/postagens")
@Validated
public class TriagemController {

  /** Um JSON por linha, sem a URL (pode ter token de URL assinada): vira o dataset de calibração e feedback. */
  private static final Logger registroDeVereditos = LoggerFactory.getLogger("validador_fotos.veredito");

  private final TriagemPostagemService triagem;
  private final MetricasTriagem metricas;
  private final ObjectMapper mapper;

  public TriagemController(TriagemPostagemService triagem, MetricasTriagem metricas, ObjectMapper mapper) {
    this.triagem = triagem;
    this.metricas = metricas;
    this.mapper = mapper;
  }

  @PostMapping("/{postagemId}/triagem")
  public ResponseEntity<VeredictoFoto> triar(@PathVariable @Min(1) int postagemId) {
    long inicio = System.nanoTime();
    VeredictoFoto veredito;
    try {
      veredito = triagem.triar(postagemId);
    } catch (ServicoOcupadoException erro) {
      metricas.pedidoRejeitado();
      throw erro;
    }
    metricas.registrar(veredito, (System.nanoTime() - inicio) / 1e9);
    ObjectNode linha = mapper.createObjectNode().put("id_postagem", postagemId);
    registroDeVereditos.info(linha.setAll((ObjectNode) mapper.valueToTree(veredito)).toString());

    if (veredito.status() != StatusTriagem.OK) {
      throw new FotoException(veredito.status(), veredito.mensagem());
    }
    return ResponseEntity.ok(veredito);
  }
}
