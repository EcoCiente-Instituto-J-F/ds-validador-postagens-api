"""Dublê do cliente Instructor: devolve a resposta pronta e guarda o que o agente mandou ao LLM."""
from types import SimpleNamespace

import instructor


class ClienteInstructorFalso(instructor.Instructor):
    def __init__(self, resposta=None, erro: Exception | None = None) -> None:  # sem super().__init__: não há provedor nem rede
        self.resposta, self.erro = resposta, erro
        self.chamadas: list[dict] = []

    @property
    def chat(self):
        return SimpleNamespace(completions=SimpleNamespace(create=self._create))

    def _create(self, **kwargs):
        self.chamadas.append(kwargs)
        if self.erro:
            raise self.erro
        return self.resposta
