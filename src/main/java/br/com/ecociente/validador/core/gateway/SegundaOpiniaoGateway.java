package br.com.ecociente.validador.core.gateway;

import java.awt.image.BufferedImage;
import java.util.Optional;

/**
 * Segunda opinião de um modelo de visão e linguagem para fotos na zona de revisão.
 * Devolve Optional vazio quando não houve parecer (provedor fora do ar): a foto segue para revisão humana.
 */
public interface SegundaOpiniaoGateway {

  Optional<Boolean> avaliar(BufferedImage imagem, String categoria);
}
