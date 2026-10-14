package br.com.ecociente.validador.core.domain;

import java.util.List;

/** Classe que o modelo de visão reconhece: uma categoria de resíduo ou o "não resíduo" (fundo). */
public record Classe(String slug, String nome, List<String> prompts) {
}
