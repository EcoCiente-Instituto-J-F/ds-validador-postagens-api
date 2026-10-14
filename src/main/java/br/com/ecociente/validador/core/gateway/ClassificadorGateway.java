package br.com.ecociente.validador.core.gateway;

import java.awt.image.BufferedImage;
import java.util.List;

import br.com.ecociente.validador.core.domain.ClasseProbabilidade;

/**
 * Modelo de visão. Qualquer implementação serve (CLIP zero-shot, cabeça treinada pela equipe...),
 * desde que devolva as classes da mais para a menos provável.
 */
public interface ClassificadorGateway {

  List<ClasseProbabilidade> ranking(BufferedImage imagem);
}
