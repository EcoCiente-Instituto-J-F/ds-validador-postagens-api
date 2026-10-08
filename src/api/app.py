"""API HTTP sobre o banco do app: triagem da foto (ValidarFotoTool), votos e decisão do síndico de uma postagem."""
import json
import logging
import secrets
import threading
import time
from collections.abc import Callable
from typing import Annotated, ContextManager, Literal

import psycopg
from fastapi import Depends, FastAPI, Header, HTTPException, Path, Request, Response
from fastapi.responses import JSONResponse
from prometheus_client import CONTENT_TYPE_LATEST
from pydantic import BaseModel, Field, ValidationError

from src import banco
from src.api.metricas import Metricas
from src.dominio.schemas import ValidarFotoInput, ValidarFotoOutput
from src.tools.validar_foto_tool import ValidarFotoConfig, ValidarFotoTool

registro_de_vereditos = logging.getLogger("validador_fotos.veredito")
STATUS_HTTP = {"pedido_invalido": 422, "foto_indisponivel": 502}
ID_INT4 = Field(ge=1, le=2_147_483_647)  # ids do banco são int4: acima disso o Postgres estoura em vez de dar 4xx
IdPostagem = Annotated[int, Path(ge=1, le=2_147_483_647)]


class Configuracoes(ValidarFotoConfig):
    """Configuração do serviço: a da tool + a chave que os serviços chamadores enviam + o banco."""

    chave_api: str = Field(min_length=16, description="Valor esperado no header X-Api-Key.")
    url_banco: str = Field(description="URL do PostgreSQL (postgresql://...).")
    max_pedidos_simultaneos: int = Field(
        default=8,
        gt=0,
        description="Pedidos em andamento (download + fila do modelo); acima disso a API responde 503 em vez de enfileirar sem fim.",
    )
    chave_api_anterior: str | None = Field(
        default=None, min_length=16, description="Chave antiga, aceita junto com a nova durante a rotação; remova depois."
    )


class Voto(BaseModel):
    usuario_id: int = ID_INT4
    tipo: Literal["aprovar", "denunciar"]
    motivo_denuncia_id: int | None = Field(default=None, ge=1, le=2_147_483_647)
    comentario: str | None = Field(default=None, max_length=255)


class Decisao(BaseModel):
    usuario_id: int = ID_INT4
    aprovar: bool


class ResultadoVoto(BaseModel):
    saldo_confianca: int
    pontuacao_ativa: bool


def criar_app(
    configuracoes: Configuracoes | None = None,
    ferramenta: ValidarFotoTool | None = None,
    conectar: Callable[[], ContextManager[psycopg.Connection]] | None = None,
) -> FastAPI:
    """Fábrica: em produção lê o ambiente e carrega o CLIP uma vez só; nos testes recebe dublês."""
    configuracoes = configuracoes or Configuracoes()
    ferramenta = ferramenta or ValidarFotoTool(configuracoes)
    conectar = conectar or (lambda: psycopg.connect(configuracoes.url_banco))  # o with do psycopg: commit, rollback ou fecha
    if not registro_de_vereditos.handlers:  # em produção ninguém configurou logging: sem isto o INFO some
        registro_de_vereditos.addHandler(logging.StreamHandler())
        registro_de_vereditos.setLevel(logging.INFO)
    app = FastAPI(title="Validador de Fotos EcoCiente", version="2.0.0")
    metricas = Metricas()
    vagas = threading.BoundedSemaphore(configuracoes.max_pedidos_simultaneos)
    chaves = [c.encode() for c in (configuracoes.chave_api, configuracoes.chave_api_anterior) if c]

    def exigir_chave_api(x_api_key: Annotated[str, Header()] = "") -> None:
        recebida = x_api_key.encode()
        if not any([secrets.compare_digest(recebida, chave) for chave in chaves]):  # sem curto-circuito: tempo constante
            raise HTTPException(status_code=401, detail="X-Api-Key inválida.")

    def erro(status: int, detalhe: str) -> Callable:
        async def tratar(requisicao: Request, excecao: Exception) -> JSONResponse:
            return JSONResponse(status_code=status, content={"detail": detalhe})

        return tratar

    app.add_exception_handler(banco.PostagemNaoEncontrada, erro(404, "Postagem não encontrada."))
    app.add_exception_handler(banco.AutoVoto, erro(403, "O autor não pode votar na própria postagem."))
    app.add_exception_handler(banco.NaoESindico, erro(403, "Só o síndico do condomínio decide postagens em análise."))
    app.add_exception_handler(psycopg.errors.UniqueViolation, erro(409, "Usuário já votou nesta postagem."))
    app.add_exception_handler(
        psycopg.errors.ForeignKeyViolation, erro(422, "Referência inválida (motivo de denúncia inexistente?).")
    )

    async def procedure_recusou(requisicao: Request, excecao: psycopg.errors.RaiseException) -> JSONResponse:
        # message_primary é a mensagem limpa do RAISE; hand-built (testes) não tem diag
        return JSONResponse(status_code=422, content={"detail": excecao.diag.message_primary or str(excecao)})

    app.add_exception_handler(psycopg.errors.RaiseException, procedure_recusou)

    async def banco_indisponivel(requisicao: Request, excecao: psycopg.OperationalError) -> JSONResponse:
        return JSONResponse(
            status_code=503,
            content={"detail": "Banco de dados indisponível; tente de novo."},
            headers={"Retry-After": "5"},
        )

    app.add_exception_handler(psycopg.OperationalError, banco_indisponivel)

    @app.get("/saude")
    def saude() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/metrics")
    def exportar_metricas() -> Response:
        return Response(metricas.exposicao(), media_type=CONTENT_TYPE_LATEST)

    @app.post("/v1/postagens/{id_postagem}/triagem", dependencies=[Depends(exigir_chave_api)])
    def triar(id_postagem: IdPostagem) -> ValidarFotoOutput:
        # rota síncrona de propósito: o FastAPI a roda numa thread e o CLIP (CPU) não trava o event loop
        with conectar() as conexao:  # conexão curta: não fica aberta durante download e inferência
            url_foto, categoria = banco.dados_triagem(conexao, id_postagem)
        try:
            pedido = ValidarFotoInput(url_foto=url_foto, categoria=categoria)
        except ValidationError:
            raise HTTPException(status_code=422, detail=f"Categoria fora do catálogo do validador: {categoria}")
        if not vagas.acquire(blocking=False):  # backpressure: o modelo atende um por vez, a fila não pode crescer sem limite
            metricas.rejeitados.inc()
            raise HTTPException(status_code=503, detail="Serviço ocupado; tente de novo.", headers={"Retry-After": "5"})
        try:
            inicio = time.perf_counter()
            resultado = ferramenta.run(pedido)
            metricas.registrar(resultado, time.perf_counter() - inicio)
        finally:
            vagas.release()
        # um JSON por linha, sem a URL (pode ter token de URL assinada): vira o dataset de calibração e feedback
        registro_de_vereditos.info(
            json.dumps({"id_postagem": id_postagem, **resultado.model_dump(exclude={"mensagem"})}, ensure_ascii=False)
        )
        if resultado.status != "ok":
            raise HTTPException(status_code=STATUS_HTTP[resultado.status], detail=resultado.mensagem)
        with conectar() as conexao:
            banco.gravar_triagem(conexao, id_postagem, resultado.pertinente, resultado.confianca)
        return resultado

    @app.post("/v1/postagens/{id_postagem}/votos", dependencies=[Depends(exigir_chave_api)])
    def registrar_voto(id_postagem: IdPostagem, voto: Voto) -> ResultadoVoto:
        with conectar() as conexao:
            saldo, ativa = banco.votar(
                conexao, id_postagem, voto.usuario_id, voto.tipo, voto.motivo_denuncia_id, voto.comentario
            )
        return ResultadoVoto(saldo_confianca=saldo, pontuacao_ativa=ativa)

    @app.post("/v1/postagens/{id_postagem}/decisao", dependencies=[Depends(exigir_chave_api)], status_code=204)
    def decidir_postagem(id_postagem: IdPostagem, decisao: Decisao) -> Response:
        with conectar() as conexao:
            banco.decidir(conexao, id_postagem, decisao.usuario_id, decisao.aprovar)
        return Response(status_code=204)

    return app
