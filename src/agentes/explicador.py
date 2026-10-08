"""Agente (Atomic Agents) que explica ao morador o resultado da triagem das fotos dele.

Fica fora da API de propósito: a API é determinística e não depende de LLM nem de chave de provedor.
Quem monta o chat do app cria o cliente Instructor do provedor escolhido e chama criar_explicador.
"""
from typing import Any

from atomic_agents import AgentConfig, AtomicAgent, BaseIOSchema
from atomic_agents.context import ChatHistory, SystemPromptGenerator
from pydantic import Field

from src.agentes.contexto import TriagensFotosCtx
from src.triagem.schemas import ValidarFotoInput
from src.triagem.tool import ValidarFotoTool


class PerguntaMoradorInput(BaseIOSchema):
    """Pergunta do morador sobre as fotos que ele enviou."""

    pergunta: str = Field(..., description="O que o morador quer saber, nas palavras dele.")


class RespostaMoradorOutput(BaseIOSchema):
    """Resposta ao morador sobre o resultado da triagem das fotos."""

    resposta: str = Field(..., description="Resposta curta, em português, baseada só nas triagens recentes.")


def criar_explicador(
    cliente: Any, modelo: str, triagens: TriagensFotosCtx, parametros_api: dict | None = None, **config_agente: Any
) -> AtomicAgent[PerguntaMoradorInput, RespostaMoradorOutput]:
    """`cliente` já vem embrulhado pelo Instructor (instructor.from_openai etc.).

    Particularidades do provedor entram em `parametros_api` (ex.: max_tokens da Anthropic) e em `config_agente`,
    repassado ao AgentConfig (ex.: mode=Mode.JSON no Groq/Ollama, assistant_role="model" no Gemini).
    """
    agente = AtomicAgent[PerguntaMoradorInput, RespostaMoradorOutput](
        config=AgentConfig(
            client=cliente,
            model=modelo,
            history=ChatHistory(),
            system_prompt_generator=SystemPromptGenerator(
                background=["Você é o assistente de reciclagem do EcoCiente e explica a triagem automática das fotos."],
                steps=[
                    "Leia a seção 'Triagens recentes de fotos': é a única fonte do que aconteceu com cada foto.",
                    "Responda à pergunta do morador usando só o que consta ali.",
                ],
                output_instructions=[
                    "Responda em português, em até 3 frases, de forma gentil.",
                    "Se não houver triagem, diga que nenhuma foto foi analisada; nunca invente um resultado.",
                    "Se a foto aguarda revisão humana, diga que ela será revisada e que ainda não há resultado.",
                    "Se a foto não foi aprovada, diga o que o modelo viu e sugira tentar de novo de mais perto e com boa luz.",
                ],
            ),
            model_api_parameters=parametros_api or {},
            **config_agente,
        )
    )
    agente.register_context_provider("triagens_fotos", triagens)
    return agente


def triar_e_responder(
    ferramenta: ValidarFotoTool,
    triagens: TriagensFotosCtx,
    agente: AtomicAgent[PerguntaMoradorInput, RespostaMoradorOutput],
    pedido: ValidarFotoInput,
    pergunta: str,
) -> RespostaMoradorOutput:
    """Valida a foto, registra o veredito no contexto do agente e responde à pergunta do morador."""
    triagens.registrar(ferramenta.run(pedido))
    return agente.run(PerguntaMoradorInput(pergunta=pergunta))
