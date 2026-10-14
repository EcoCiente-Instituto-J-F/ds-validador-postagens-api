package br.com.ecociente.validador.core.domain;

import java.awt.image.BufferedImage;

/**
 * Foto baixada e pronta para o modelo (RGB, em pé).
 * semExif: não traz metadados EXIF (indício fraco: apps de mensagem também removem EXIF).
 */
public record FotoCarregada(BufferedImage imagem, boolean semExif) {
}
