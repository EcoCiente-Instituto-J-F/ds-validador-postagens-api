package br.com.ecociente.validador.core.domain;

/** O que a triagem precisa da postagem: a URL da foto e o nome da categoria escolhida pelo morador. */
public record DadosTriagem(String urlFoto, String nomeCategoria) {
}
