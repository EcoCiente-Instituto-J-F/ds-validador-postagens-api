"""ValidarFotoTool (Atomic Agents): baixa a foto, roda o modelo de visão e aplica a regra de pertinência."""
import httpx
from atomic_agents import BaseTool
from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from src.agentes.segunda_opiniao import SegundaOpiniao
from src.triagem.categorias import CATEGORIAS, normalizar_categoria
from src.triagem.pertinencia import decidir_pertinencia
from src.triagem.schemas import ValidarFotoInput, ValidarFotoOutput, rejeitar_foto
from src.triagem.cabeca import CabecaTreinada, ClassificadorCabeca
from src.triagem.classificador import MODELO_PADRAO, Classificador, ClassificadorClip
from src.triagem.hash_perceptual import dhash
from src.triagem.sinais import sem_exif, tem_padrao_de_tela
from src.triagem.foto import FotoIndisponivel, FotoInvalida, PedidoInvalido, abrir_imagem, baixar_foto


class ValidarFotoConfig(BaseSettings):
    """Parâmetros da tool; o que não for passado vem das variáveis de ambiente (ou do .env).

    Não herda BaseToolConfig de propósito: os campos title/description dele seriam lidos do
    ambiente (TITLE, DESCRIPTION) e trocariam em silêncio o nome e a descrição que o LLM vê.
    """

    model_config = SettingsConfigDict(env_file=".env", extra="ignore", env_ignore_empty=True)  # VAR= vazia = ausente

    hosts_permitidos: str = Field(..., description="Hosts do storage de fotos, separados por vírgula (anti-SSRF).")
    limiar_confianca: float = Field(default=0.5, ge=0, le=1, description="Probabilidade mínima da categoria para aprovar.")
    limiar_revisao: float | None = Field(
        default=None,
        ge=0,
        le=1,
        description="Se definido, categoria certa com probabilidade entre este valor e o limiar vai para revisão humana (pertinente=None).",
    )
    limiares_por_categoria: dict[str, float] = Field(
        default_factory=dict,
        description='Limiar próprio por categoria, em JSON no ambiente: {"vidro": 0.6}. As demais usam limiar_confianca.',
    )
    tamanho_maximo_bytes: int = Field(default=10_000_000, gt=0, description="Tamanho máximo da foto, em bytes.")
    timeout_download_segundos: float = Field(default=10.0, gt=0, description="Timeout do download no storage.")
    cabeca_treinada: str | None = Field(
        default=None,
        description="Caminho do .npz gerado por src.calibracao.treinar; se definido, substitui o zero-shot.",
    )
    modelo_clip: str = Field(
        default=MODELO_PADRAO,
        description="Modelo CLIP do Hugging Face; SigLIP não serve (usa sigmoid e quebra a soma por classe).",
    )

    @field_validator("limiares_por_categoria")
    @classmethod
    def limiares_do_catalogo(cls, valor: dict[str, float]) -> dict[str, float]:
        normalizado = {normalizar_categoria(chave): limiar for chave, limiar in valor.items()}
        desconhecidas = set(normalizado) - set(CATEGORIAS)
        if desconhecidas or any(not 0 <= limiar <= 1 for limiar in normalizado.values()):
            raise ValueError(f"limiares_por_categoria: categorias aceitas {', '.join(CATEGORIAS)}, valores entre 0 e 1")
        return normalizado

    def limiar_para(self, categoria: str) -> float:
        return self.limiares_por_categoria.get(categoria, self.limiar_confianca)

    @property
    def conjunto_hosts_permitidos(self) -> frozenset[str]:
        return frozenset(host.strip().lower() for host in self.hosts_permitidos.split(",") if host.strip())


class ValidarFotoTool(BaseTool[ValidarFotoInput, ValidarFotoOutput]):
    """Baixa a foto de uma postagem, roda o modelo de visão e diz se ela combina com a categoria escolhida."""

    def __init__(
        self,
        config: ValidarFotoConfig,
        classificador: Classificador | None = None,
        cliente_http: httpx.Client | None = None,
        segunda_opiniao: SegundaOpiniao | None = None,
    ) -> None:
        super().__init__()  # BaseToolConfig padrão: nome e descrição da tool vêm dos schemas
        self.parametros = config
        self.classificador = classificador or self._classificador_da_config(config)  # carrega o CLIP uma vez
        self.segunda_opiniao = segunda_opiniao  # opcional: só é consultada na zona de revisão
        self.cliente_http = cliente_http or httpx.Client(timeout=config.timeout_download_segundos)

    @staticmethod
    def _classificador_da_config(config: ValidarFotoConfig) -> Classificador:
        clip = ClassificadorClip(config.modelo_clip)
        if config.cabeca_treinada:
            cabeca = CabecaTreinada.carregar(config.cabeca_treinada)
            if cabeca.modelo != config.modelo_clip:
                raise ValueError(f"A cabeça foi treinada com {cabeca.modelo}, mas modelo_clip={config.modelo_clip}.")
            return ClassificadorCabeca(clip, cabeca)
        return clip

    def _consultar_segunda_opiniao(self, veredito: ValidarFotoOutput, imagem) -> ValidarFotoOutput:
        parecer = self.segunda_opiniao.avaliar(imagem, veredito.categoria_informada)
        if parecer is None:  # provedor falhou: segue para revisão humana
            return veredito
        nome = CATEGORIAS[veredito.categoria_informada].nome
        mensagem = (
            f"Foto compatível com {nome} (confirmada em segunda análise)."
            if parecer
            else f"Não deu para confirmar que a foto é de {nome}. Tente de mais perto e com boa luz."
        )
        return veredito.model_copy(update={"pertinente": parecer, "segunda_opiniao": True, "mensagem": mensagem})

    def run(self, params: ValidarFotoInput) -> ValidarFotoOutput:
        config = self.parametros
        try:
            dados = baixar_foto(
                params.url_foto,
                hosts_permitidos=config.conjunto_hosts_permitidos,
                tamanho_maximo=config.tamanho_maximo_bytes,
                cliente=self.cliente_http,
            )
            imagem = abrir_imagem(dados)
        except PedidoInvalido as erro:
            return rejeitar_foto(params.categoria, str(erro), status="pedido_invalido")
        except FotoIndisponivel as erro:
            return rejeitar_foto(params.categoria, str(erro), status="foto_indisponivel")
        except FotoInvalida as erro:
            return rejeitar_foto(params.categoria, str(erro))
        ranking = self.classificador.ranking(imagem)
        veredito = decidir_pertinencia(params.categoria, ranking, config.limiar_para(params.categoria), config.limiar_revisao)
        if veredito.pertinente is None and self.segunda_opiniao:
            veredito = self._consultar_segunda_opiniao(veredito, imagem)
        sinais = [nome for nome, ativo in (("padrao_de_tela", tem_padrao_de_tela(imagem)), ("sem_exif", sem_exif(dados))) if ativo]
        return veredito.model_copy(update={"hash_foto": dhash(imagem), "sinais": sinais})
