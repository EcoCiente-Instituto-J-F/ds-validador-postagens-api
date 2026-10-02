"""API HTTP: POST /v1/validacoes expõe a ValidarFotoTool para os serviços do cluster."""
import json
import logging
import secrets
import threading
import time
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, Response
from prometheus_client import CONTENT_TYPE_LATEST
from pydantic import Field

from src.api.metricas import Metricas
from src.dominio.schemas import ValidarFotoInput, ValidarFotoOutput
from src.tools.validar_foto_tool import ValidarFotoConfig, ValidarFotoTool

registro_de_vereditos = logging.getLogger("validador_fotos.veredito")
STATUS_HTTP = {"pedido_invalido": 422, "foto_indisponivel": 502}


class Configuracoes(ValidarFotoConfig):
    """Configuração do serviço: a da tool + a chave que os serviços chamadores enviam."""

    chave_api: str = Field(min_length=16, description="Valor esperado no header X-Api-Key.")
    max_pedidos_simultaneos: int = Field(
        default=8,
        gt=0,
        description="Pedidos em andamento (download + fila do modelo); acima disso a API responde 503 em vez de enfileirar sem fim.",
    )
    chave_api_anterior: str | None = Field(
        default=None, min_length=16, description="Chave antiga, aceita junto com a nova durante a rotação; remova depois."
    )


def criar_app(configuracoes: Configuracoes | None = None, ferramenta: ValidarFotoTool | None = None) -> FastAPI:
    """Fábrica: em produção lê o ambiente e carrega o CLIP uma vez só; nos testes recebe dublês."""
    configuracoes = configuracoes or Configuracoes()
    ferramenta = ferramenta or ValidarFotoTool(configuracoes)
    if not registro_de_vereditos.handlers:  # em produção ninguém configurou logging: sem isto o INFO some
        registro_de_vereditos.addHandler(logging.StreamHandler())
        registro_de_vereditos.setLevel(logging.INFO)
    app = FastAPI(title="Validador de Fotos EcoCiente", version="1.1.0")
    metricas = Metricas()
    vagas = threading.BoundedSemaphore(configuracoes.max_pedidos_simultaneos)
    chaves = [c.encode() for c in (configuracoes.chave_api, configuracoes.chave_api_anterior) if c]

    def exigir_chave_api(x_api_key: Annotated[str, Header()] = "") -> None:
        recebida = x_api_key.encode()
        if not any([secrets.compare_digest(recebida, chave) for chave in chaves]):  # sem curto-circuito: tempo constante
            raise HTTPException(status_code=401, detail="X-Api-Key inválida.")

    @app.get("/saude")
    def saude() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/metrics")
    def exportar_metricas() -> Response:
        return Response(metricas.exposicao(), media_type=CONTENT_TYPE_LATEST)

    @app.post("/v1/validacoes", dependencies=[Depends(exigir_chave_api)])
    def validar(pedido: ValidarFotoInput) -> ValidarFotoOutput:
        # rota síncrona de propósito: o FastAPI a roda numa thread e o CLIP (CPU) não trava o event loop
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
        registro_de_vereditos.info(json.dumps(resultado.model_dump(exclude={"mensagem"}), ensure_ascii=False))
        if resultado.status != "ok":
            raise HTTPException(status_code=STATUS_HTTP[resultado.status], detail=resultado.mensagem)
        return resultado

    return app
