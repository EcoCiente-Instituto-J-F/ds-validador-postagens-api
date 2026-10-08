import contextlib
import logging

import psycopg
import pytest

from src import banco, fechamento


class ConexaoFalsa:
    def transaction(self):
        return contextlib.nullcontext()


def test_encerra_todas_as_vencidas(monkeypatch):
    encerradas = []
    monkeypatch.setattr(banco, "janelas_vencidas", lambda conexao: [1, 2, 3])
    monkeypatch.setattr(banco, "encerrar_janela", lambda conexao, id_: encerradas.append(id_))
    assert fechamento.fechar_vencidas(ConexaoFalsa()) == 0
    assert encerradas == [1, 2, 3]


def test_uma_falha_nao_para_as_outras(monkeypatch, caplog):
    encerradas = []

    def encerrar(conexao, id_):
        if id_ == 2:
            raise psycopg.errors.RaiseException("falhou")
        encerradas.append(id_)

    monkeypatch.setattr(banco, "janelas_vencidas", lambda conexao: [1, 2, 3])
    monkeypatch.setattr(banco, "encerrar_janela", encerrar)
    with caplog.at_level(logging.INFO, logger="validador_fotos.fechamento"):
        assert fechamento.fechar_vencidas(ConexaoFalsa()) == 1
    assert encerradas == [1, 3]
    assert any("2" in r.getMessage() and r.levelno == logging.ERROR for r in caplog.records)


def test_main_sai_com_1_quando_ha_falha(monkeypatch):
    monkeypatch.setenv("URL_BANCO", "postgresql://x")
    monkeypatch.setattr(psycopg, "connect", lambda *a, **k: contextlib.nullcontext(ConexaoFalsa()))
    monkeypatch.setattr(fechamento, "fechar_vencidas", lambda conexao: 1)
    with pytest.raises(SystemExit) as saida:
        fechamento.main()
    assert saida.value.code == 1
