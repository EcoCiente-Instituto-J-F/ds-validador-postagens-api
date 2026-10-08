import os
from pathlib import Path
from types import SimpleNamespace

import psycopg
import pytest
from psycopg.conninfo import conninfo_to_dict

SQL = Path(__file__).parent.parent / "sql"


@pytest.fixture
def conexao():
    url = os.environ.get("URL_BANCO_TESTE")
    if not url:
        pytest.skip("URL_BANCO_TESTE não definida")
    # o fixture dá DROP SCHEMA public: nunca contra um banco que não seja local/descartável
    if conninfo_to_dict(url).get("host") not in ("localhost", "127.0.0.1"):
        pytest.skip("URL_BANCO_TESTE precisa apontar para localhost ou 127.0.0.1 (o teste apaga o schema public)")
    with psycopg.connect(url, autocommit=True) as conn:
        # schema novo a cada teste: isolamento sem depender de rollback
        conn.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public")
        conn.execute((SQL / "ecociente_schema.sql").read_text(encoding="utf-8"))
        conn.execute((SQL / "seed_lookups.sql").read_text(encoding="utf-8"))
        yield conn


def _usuario(conn, n):
    endereco = conn.execute("INSERT INTO tb_enderecos (cidade) VALUES ('X') RETURNING id_endereco").fetchone()[0]
    return conn.execute(
        "INSERT INTO tb_usuarios (nome_usuario, email_usuario, senha_hash, tipo_usuario_id, endereco_id)"
        " VALUES (%s, %s, 'x', 1, %s) RETURNING id_usuario",
        (f"u{n}", f"u{n}@exemplo.com", endereco),
    ).fetchone()[0]


def _vinculo(conn, usuario, condominio, nivel):
    conn.execute(
        "INSERT INTO tb_rel_usuarios_condominios (usuario_id, condominio_id, aprovado, nivel_confianca_id,"
        " trust_score, postagens_validadas_sem_contestacao, denuncias_realizadas, denuncias_procedentes)"
        " VALUES (%s, %s, TRUE, (SELECT id_nivel_confianca FROM tb_lkp_niveis_confianca WHERE nome_nivel = %s), 0, 0, 0, 0)",
        (usuario, condominio, nivel),
    )


def nova_postagem(conn, autor, condominio, hash_foto) -> int:
    return conn.execute(
        "INSERT INTO tb_postagens (usuario_id, condominio_id, categoria_id, url_foto, hash_foto, capturada_em)"
        " VALUES (%s, %s, (SELECT id_categoria FROM tb_lkp_categorias_residuos WHERE nome_categoria = 'Plástico'),"
        " 'https://storage.exemplo.com/postagens/1.png', %s, now() - interval '1 minute') RETURNING id_postagem",
        (autor, condominio, hash_foto),
    ).fetchone()[0]


def vencer(conn, id_postagem):
    conn.execute(
        "UPDATE tb_postagens SET data_limite_analise = now() - interval '1 minute' WHERE id_postagem = %s",
        (id_postagem,),
    )


@pytest.fixture
def mundo(conexao):
    c = conexao
    sindico = _usuario(c, "sindico")
    id_sindico = c.execute("INSERT INTO tb_sindicos (usuario_id) VALUES (%s) RETURNING id_sindico", (sindico,)).fetchone()[0]
    condominio = c.execute(
        "INSERT INTO tb_condominios (nome_condominio, tipo_condominio_id, sindico_id) VALUES ('Cond', 1, %s)"
        " RETURNING id_condominio",
        (id_sindico,),
    ).fetchone()[0]
    _vinculo(c, sindico, condominio, "sindico")
    autor = _usuario(c, "autor")
    _vinculo(c, autor, condominio, "morador_comum")
    comuns = [_usuario(c, f"comum{i}") for i in range(5)]
    for u in comuns:
        _vinculo(c, u, condominio, "morador_comum")
    confiaveis = [_usuario(c, f"confiavel{i}") for i in range(4)]
    for u in confiaveis:
        _vinculo(c, u, condominio, "pessoa_confiavel")
    return SimpleNamespace(
        condominio=condominio,
        sindico=sindico,
        autor=autor,
        comuns=comuns,
        confiaveis=confiaveis,
        sem_vinculo=_usuario(c, "semvinculo"),
        postagem=nova_postagem(c, autor, condominio, "hash-mundo"),
    )
