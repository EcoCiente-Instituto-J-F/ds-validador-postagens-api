package br.com.ecociente.validador.entrypoint.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.ecociente.validador.core.service.PostagemValidacaoService;
import br.com.ecociente.validador.entrypoint.dto.request.VotoRequest;
import br.com.ecociente.validador.entrypoint.dto.response.ResultadoVotoResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/v1/postagens")
@Validated
public class VotosController {

  private static final int MAX_ID_INT4 = 2_147_483_647;

  private final PostagemValidacaoService service;

  public VotosController(PostagemValidacaoService service) {
    this.service = service;
  }

  @PostMapping("/{postagemId}/votos")
  public ResponseEntity<ResultadoVotoResponse> votar(
      @PathVariable @Min(1) @Max(MAX_ID_INT4) int postagemId,
      @Valid @RequestBody VotoRequest voto) {
    var resultado = service.votar(
        postagemId, voto.usuarioId(), voto.tipo(), voto.motivoDenunciaId(), voto.comentario());
    return ResponseEntity.ok(ResultadoVotoResponse.de(resultado));
  }
}
