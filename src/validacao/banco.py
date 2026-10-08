"""Acesso ao PostgreSQL: chama os procedures do schema. Quem abre a conexão decide o commit."""

import psycopg


class PostagemNaoEncontrada(Exception):
    pass


class AutoVoto(Exception):
    pass


class NaoESindico(Exception):
    pass


def dados_triagem(conexao: psycopg.Connection, id_postagem: int) -> tuple[str, str]:
    linha = conexao.execute(
        "SELECT p.url_foto, c.nome_categoria FROM tb_postagens p"
        " JOIN tb_lkp_categorias_residuos c ON c.id_categoria = p.categoria_id"
        " WHERE p.id_postagem = %s",
        (id_postagem,),
    ).fetchone()
    if linha is None:
        raise PostagemNaoEncontrada(id_postagem)
    return linha[0], linha[1]


def gravar_triagem(
    conexao: psycopg.Connection, id_postagem: int, aprovada: bool | None, confianca: float | None
) -> None:
    conexao.execute(
        "UPDATE tb_postagens SET triagem_automatica_aprovada = %s, triagem_automatica_confianca = %s"
        " WHERE id_postagem = %s",
        (aprovada, confianca, id_postagem),
    )


def _autor(conexao: psycopg.Connection, id_postagem: int) -> int:
    linha = conexao.execute("SELECT usuario_id FROM tb_postagens WHERE id_postagem = %s", (id_postagem,)).fetchone()
    if linha is None:
        raise PostagemNaoEncontrada(id_postagem)
    return linha[0]


def votar(
    conexao: psycopg.Connection,
    id_postagem: int,
    usuario_id: int,
    tipo: str,
    motivo_denuncia_id: int | None,
    comentario: str | None,
) -> tuple[int, bool]:
    if _autor(conexao, id_postagem) == usuario_id:
        raise AutoVoto(usuario_id)
    conexao.execute(
        "CALL sp_processar_voto_postagem(%s, %s, %s::varchar, %s::int, %s::varchar)",
        (id_postagem, usuario_id, tipo, motivo_denuncia_id, comentario),
    )
    return tuple(
        conexao.execute(
            "SELECT saldo_confianca, pontuacao_ativa FROM tb_postagens WHERE id_postagem = %s", (id_postagem,)
        ).fetchone()
    )


def atualizar_trust_scores(conexao: psycopg.Connection, id_postagem: int) -> None:
    # JOIN no vínculo: quem saiu do condomínio (ou nunca entrou) não tem trust score a atualizar
    envolvidos = conexao.execute(
        "SELECT DISTINCT p.condominio_id, e.usuario_id FROM tb_postagens p"
        " CROSS JOIN LATERAL (SELECT p.usuario_id AS usuario_id"
        "   UNION SELECT v.usuario_id FROM tb_rel_votos_postagens v WHERE v.postagem_id = p.id_postagem) e"
        " JOIN tb_rel_usuarios_condominios r ON r.usuario_id = e.usuario_id AND r.condominio_id = p.condominio_id"
        " WHERE p.id_postagem = %s"
        " ORDER BY e.usuario_id",  # ordem fixa dos locks: evita deadlock entre /decisao e o job
        (id_postagem,),
    ).fetchall()
    for condominio_id, usuario_id in envolvidos:
        conexao.execute("CALL sp_atualizar_trust_score(%s, %s)", (usuario_id, condominio_id))


def decidir(conexao: psycopg.Connection, id_postagem: int, usuario_id: int, aprovar: bool) -> None:
    linha = conexao.execute(
        "SELECT s.usuario_id FROM tb_postagens p"
        " JOIN tb_condominios c ON c.id_condominio = p.condominio_id"
        " LEFT JOIN tb_sindicos s ON s.id_sindico = c.sindico_id"
        " WHERE p.id_postagem = %s",
        (id_postagem,),
    ).fetchone()
    if linha is None:
        raise PostagemNaoEncontrada(id_postagem)
    if linha[0] != usuario_id:
        raise NaoESindico(usuario_id)
    conexao.execute("CALL sp_decidir_postagem_analise(%s, %s)", (id_postagem, aprovar))
    atualizar_trust_scores(conexao, id_postagem)


def janelas_vencidas(conexao: psycopg.Connection, limite: int = 1000) -> list[int]:
    linhas = conexao.execute(
        "SELECT id_postagem FROM tb_postagens WHERE resolvido_em IS NULL AND data_limite_analise <= now()"
        " ORDER BY data_limite_analise LIMIT %s",
        (limite,),
    ).fetchall()
    return [linha[0] for linha in linhas]


def encerrar_janela(conexao: psycopg.Connection, id_postagem: int) -> None:
    conexao.execute("CALL sp_encerrar_janela_postagem(%s)", (id_postagem,))
    atualizar_trust_scores(conexao, id_postagem)
