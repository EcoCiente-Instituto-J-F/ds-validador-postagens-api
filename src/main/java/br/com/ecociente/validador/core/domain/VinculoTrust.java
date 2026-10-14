package br.com.ecociente.validador.core.domain;

/** Usuário envolvido numa postagem que tem vínculo com o condomínio (quem tem trust score a atualizar). */
public record VinculoTrust(int condominioId, int usuarioId) {
}
