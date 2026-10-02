"""API HTTP: POST /v1/validacoes expõe a ValidarFotoTool para os serviços do cluster."""
import secrets
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import Field

from src.validar_foto_tool import ValidarFotoConfig, ValidarFotoInput, ValidarFotoOutput, ValidarFotoTool

STATUS_HTTP = {"pedido_invalido": 422, "foto_indisponivel": 502}


class Configuracoes(ValidarFotoConfig):
    """Configuração do serviço: a da tool + a chave que os serviços chamadores enviam."""

    chave_api: str = Field(min_length=16, description="Valor esperado no header X-Api-Key.")


def criar_app(configuracoes: Configuracoes | None = None, ferramenta: ValidarFotoTool | None = None) -> FastAPI:
    """Fábrica: em produção lê o ambiente e carrega o CLIP uma vez só; nos testes recebe dublês."""
    configuracoes = configuracoes or Configuracoes()
    ferramenta = ferramenta or ValidarFotoTool(configuracoes)
    app = FastAPI(title="Validador de Fotos EcoCiente", version="1.0.0")

    def exigir_chave_api(x_api_key: Annotated[str, Header()] = "") -> None:
        if not secrets.compare_digest(x_api_key.encode(), configuracoes.chave_api.encode()):
            raise HTTPException(status_code=401, detail="X-Api-Key inválida.")

    @app.get("/saude")
    def saude() -> dict[str, str]:
        return {"status": "ok"}

    @app.post("/v1/validacoes", dependencies=[Depends(exigir_chave_api)])
    def validar(pedido: ValidarFotoInput) -> ValidarFotoOutput:
        # rota síncrona de propósito: o FastAPI a roda numa thread e o CLIP (CPU) não trava o event loop
        resultado = ferramenta.run(pedido)
        if resultado.status != "ok":
            raise HTTPException(status_code=STATUS_HTTP[resultado.status], detail=resultado.mensagem)
        return resultado

    return app
