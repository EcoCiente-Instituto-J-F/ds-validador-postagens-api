package br.com.ecociente.validador.entrypoint.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.ecociente.validador.core.service.PostagemValidacaoService;
import br.com.ecociente.validador.entrypoint.dto.request.DecisaoRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/v1/postagens")
@Validated
public class DecisaoController {

  private static final int MAX_ID_INT4 = 2_147_483_647;

  private final PostagemValidacaoService service;

  public DecisaoController(PostagemValidacaoService service) {
    this.service = service;
  }

  @PostMapping("/{postagemId}/decisao")
  public ResponseEntity<Void> decidir(
      @PathVariable @Min(1) @Max(MAX_ID_INT4) int postagemId,
      @Valid @RequestBody DecisaoRequest decisao) {
    service.decidir(postagemId, decisao.usuarioId(), decisao.aprovar());
    return ResponseEntity.noContent().build();
  }
}
