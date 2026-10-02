"""Context provider do Atomic Agents: põe no prompt do agente as últimas triagens de foto."""
from collections import deque

from atomic_agents.context import BaseDynamicContextProvider

from src.validar_foto_tool import ValidarFotoOutput


class TriagensFotosCtx(BaseDynamicContextProvider):
    """Guarda as 5 últimas triagens para o agente explicar ao morador o que aconteceu com cada foto."""

    def __init__(self) -> None:
        super().__init__(title="Triagens recentes de fotos")
        self.triagens: deque[ValidarFotoOutput] = deque(maxlen=5)

    def registrar(self, resultado: ValidarFotoOutput) -> None:
        self.triagens.append(resultado)

    def get_info(self) -> str:
        if not self.triagens:
            return "Nenhuma foto foi analisada nesta conversa."
        linhas = []
        for triagem in self.triagens:
            if triagem.status != "ok":
                veredito = f"não analisada ({triagem.status})"
            else:
                veredito = "pertinente" if triagem.pertinente else "não pertinente"
            if triagem.confianca is not None:
                veredito += f"; o modelo viu {triagem.categoria_detectada} com {triagem.confianca:g}% de confiança"
            linhas.append(f"- Foto de {triagem.categoria_informada}: {veredito}. {triagem.mensagem}")
        return "\n".join(linhas)
