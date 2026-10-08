from decimal import Decimal

import psycopg
import pytest

from src import banco, fechamento
from tests.banco.conftest import nova_postagem, vencer

pytestmark = pytest.mark.banco


def _postagem(conexao, id_postagem, colunas):
    return conexao.execute(f"SELECT {colunas} FROM tb_postagens WHERE id_postagem = %s", (id_postagem,)).fetchone()


def _status(conexao, id_postagem):
    return conexao.execute(
        "SELECT s.nome_status, p.resolvido_em IS NOT NULL FROM tb_postagens p"
        " JOIN tb_lkp_status_validacoes_postagens s ON s.id_status_validacao = p.status_validacao_id"
        " WHERE p.id_postagem = %s",
        (id_postagem,),
    ).fetchone()


def test_schema_sobe(conexao):
    assert conexao.execute("SELECT count(*) FROM tb_lkp_niveis_confianca").fetchone()[0] == 3


def test_dados_triagem_le_url_e_categoria(conexao, mundo):
    assert banco.dados_triagem(conexao, mundo.postagem) == ("https://storage.exemplo.com/postagens/1.png", "Plástico")


def test_postagem_inexistente(conexao, mundo):
    with pytest.raises(banco.PostagemNaoEncontrada):
        banco.dados_triagem(conexao, 999_999)
    with pytest.raises(banco.PostagemNaoEncontrada):
        banco.votar(conexao, 999_999, mundo.comuns[0], "aprovar", None, None)
    with pytest.raises(banco.PostagemNaoEncontrada):
        banco.decidir(conexao, 999_999, mundo.sindico, True)


def test_gravar_triagem_inclusive_nulo(conexao, mundo):
    colunas = "triagem_automatica_aprovada, triagem_automatica_confianca"
    banco.gravar_triagem(conexao, mundo.postagem, False, 87.42)
    assert _postagem(conexao, mundo.postagem, colunas) == (False, Decimal("87.42"))
    banco.gravar_triagem(conexao, mundo.postagem, None, None)
    assert _postagem(conexao, mundo.postagem, colunas) == (None, None)


def test_voto_soma_o_peso_do_nivel(conexao, mundo):
    assert banco.votar(conexao, mundo.postagem, mundo.comuns[0], "aprovar", None, None) == (1, True)
    assert banco.votar(conexao, mundo.postagem, mundo.confiaveis[0], "denunciar", 1, "não é lixo") == (-2, True)


def test_autor_nao_vota_na_propria_postagem(conexao, mundo):
    with pytest.raises(banco.AutoVoto):
        banco.votar(conexao, mundo.postagem, mundo.autor, "aprovar", None, None)
    assert conexao.execute("SELECT count(*) FROM tb_rel_votos_postagens").fetchone()[0] == 0


def test_voto_repetido(conexao, mundo):
    banco.votar(conexao, mundo.postagem, mundo.comuns[0], "aprovar", None, None)
    with pytest.raises(psycopg.errors.UniqueViolation):
        banco.votar(conexao, mundo.postagem, mundo.comuns[0], "denunciar", 1, None)


def test_denuncia_sem_motivo_e_janela_encerrada_viram_raise_exception(conexao, mundo):
    with pytest.raises(psycopg.errors.RaiseException):
        banco.votar(conexao, mundo.postagem, mundo.comuns[0], "denunciar", None, None)
    vencer(conexao, mundo.postagem)
    with pytest.raises(psycopg.errors.RaiseException):
        banco.votar(conexao, mundo.postagem, mundo.comuns[1], "aprovar", None, None)


def test_histerese_retira_em_menos_5_e_restaura_em_zero(conexao, mundo):
    banco.votar(conexao, mundo.postagem, mundo.confiaveis[0], "denunciar", 1, None)
    assert banco.votar(conexao, mundo.postagem, mundo.confiaveis[1], "denunciar", 1, None) == (-6, False)
    banco.votar(conexao, mundo.postagem, mundo.confiaveis[2], "aprovar", None, None)
    assert banco.votar(conexao, mundo.postagem, mundo.confiaveis[3], "aprovar", None, None) == (0, True)
    assert _postagem(conexao, mundo.postagem, "pontuacao_reconciliacao_pendente") == (True,)


@pytest.mark.parametrize(("denuncias_comuns", "status"), [(0, "aprovada"), (1, "em_analise"), (5, "reprovada")])
def test_encerrar_janela_nas_tres_faixas(conexao, mundo, denuncias_comuns, status):
    for usuario in mundo.comuns[:denuncias_comuns]:
        banco.votar(conexao, mundo.postagem, usuario, "denunciar", 1, None)
    vencer(conexao, mundo.postagem)
    assert banco.janelas_vencidas(conexao) == [mundo.postagem]
    banco.encerrar_janela(conexao, mundo.postagem)
    assert _status(conexao, mundo.postagem) == (status, True)
    assert banco.janelas_vencidas(conexao) == []


def test_janela_aberta_nao_aparece_como_vencida(conexao, mundo):
    assert banco.janelas_vencidas(conexao) == []


def test_denuncia_procedente_conta_no_trust_score(conexao, mundo):
    for usuario in mundo.comuns:
        banco.votar(conexao, mundo.postagem, usuario, "denunciar", 1, None)
    vencer(conexao, mundo.postagem)
    banco.encerrar_janela(conexao, mundo.postagem)
    linha = conexao.execute(
        "SELECT denuncias_realizadas, denuncias_procedentes, trust_score FROM tb_rel_usuarios_condominios"
        " WHERE usuario_id = %s AND condominio_id = %s",
        (mundo.comuns[0], mundo.condominio),
    ).fetchone()
    assert linha == (1, 1, Decimal("55.00"))


def test_fechamento_ignora_envolvido_sem_vinculo(conexao, mundo):
    postagem = nova_postagem(conexao, mundo.sem_vinculo, mundo.condominio, "hash-sem-vinculo")
    vencer(conexao, postagem)
    banco.encerrar_janela(conexao, postagem)  # não levanta
    assert _status(conexao, postagem)[0] == "aprovada"


def test_decisao_so_do_sindico_e_so_em_analise(conexao, mundo):
    with pytest.raises(banco.NaoESindico):
        banco.decidir(conexao, mundo.postagem, mundo.comuns[0], True)
    with pytest.raises(psycopg.errors.RaiseException):  # ainda não está em análise
        banco.decidir(conexao, mundo.postagem, mundo.sindico, True)
    banco.votar(conexao, mundo.postagem, mundo.comuns[0], "denunciar", 1, None)
    vencer(conexao, mundo.postagem)
    banco.encerrar_janela(conexao, mundo.postagem)
    banco.decidir(conexao, mundo.postagem, mundo.sindico, False)
    assert _status(conexao, mundo.postagem)[0] == "reprovada"
    assert _postagem(conexao, mundo.postagem, "pontuacao_ativa") == (False,)


def test_job_fecha_as_vencidas(conexao, mundo):
    outra = nova_postagem(conexao, mundo.autor, mundo.condominio, "hash-2")  # janela aberta
    vencer(conexao, mundo.postagem)
    assert fechamento.fechar_vencidas(conexao) == 0
    assert _status(conexao, mundo.postagem)[1] is True
    assert _status(conexao, outra)[1] is False
