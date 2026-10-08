"""Métricas Prometheus do serviço. Registro próprio por app: testes criam vários apps sem colidir."""
from prometheus_client import CollectorRegistry, Counter, Histogram, generate_latest

from src.triagem.schemas import ValidarFotoOutput

PERTINENTE = {True: "sim", False: "nao", None: "sem_veredito"}


class Metricas:
    def __init__(self) -> None:
        self.registro = CollectorRegistry()
        self.validacoes = Counter(
            "validacoes_total", "Vereditos por categoria, status e pertinência.", ["categoria", "status", "pertinente"], registry=self.registro
        )
        self.rejeitados = Counter("pedidos_rejeitados_total", "Pedidos recusados com 503 por excesso de carga.", registry=self.registro)
        self.duracao = Histogram("validacao_segundos", "Tempo de cada validação (download + modelo).", registry=self.registro)
        # a média e a distribuição da confiança é o alerta de deriva: se cair, mudou a foto ou o modelo
        self.confianca = Histogram(
            "confianca_modelo", "Confiança do modelo (0–100).", ["categoria"], buckets=range(10, 101, 10), registry=self.registro
        )

    def registrar(self, resultado: ValidarFotoOutput, segundos: float) -> None:
        categoria = resultado.categoria_informada
        self.validacoes.labels(categoria, resultado.status, PERTINENTE[resultado.pertinente]).inc()
        self.duracao.observe(segundos)
        if resultado.confianca is not None:
            self.confianca.labels(categoria).observe(resultado.confianca)

    def exposicao(self) -> bytes:
        return generate_latest(self.registro)
