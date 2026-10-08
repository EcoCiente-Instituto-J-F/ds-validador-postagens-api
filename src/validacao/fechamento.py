"""Job (CronJob): encerra as janelas de validação de 24h vencidas. Não carrega o CLIP."""

import logging
import os
import sys

import psycopg

from src.validacao import banco

log = logging.getLogger("validador_fotos.fechamento")


def fechar_vencidas(conexao: psycopg.Connection) -> int:
    """Recebe conexão autocommit; cada janela numa transação própria. Devolve o número de falhas."""
    ids = banco.janelas_vencidas(conexao)
    falhas = 0
    for id_postagem in ids:
        try:
            with conexao.transaction():
                banco.encerrar_janela(conexao, id_postagem)
        except psycopg.Error:
            # uma janela com erro não pode travar as demais
            log.exception("falha ao encerrar a janela da postagem %s", id_postagem)
            falhas += 1
    log.info("janelas encerradas: %s, falhas: %s", len(ids) - falhas, falhas)
    return falhas


def main() -> None:
    logging.basicConfig(level=logging.INFO)
    with psycopg.connect(os.environ["URL_BANCO"], autocommit=True) as conexao:
        falhas = fechar_vencidas(conexao)
    sys.exit(1 if falhas else 0)


if __name__ == "__main__":
    main()
