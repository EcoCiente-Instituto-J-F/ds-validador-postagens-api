import httpx
import numpy as np
import pytest
from pydantic import ValidationError

from src.dominio.schemas import ValidarFotoInput, ValidarFotoOutput
from src.tools.validar_foto_tool import ValidarFotoConfig, ValidarFotoTool
from src.visao.cabeca import ClassificadorCabeca, treinar_cabeca
from src.visao.classificador import MODELO_PADRAO

# ---------- ValidarFotoTool ----------

URL_FOTO = "https://storage.exemplo.com/postagens/42.png"


def montar_tool(conteudo: bytes, classificador, status_storage: int = 200, **parametros) -> ValidarFotoTool:
    config = ValidarFotoConfig(_env_file=None, hosts_permitidos="storage.exemplo.com", **parametros)
    storage = httpx.Client(transport=httpx.MockTransport(lambda _: httpx.Response(status_storage, content=conteudo)))
    return ValidarFotoTool(config, classificador, storage)


def test_tool_devolve_o_veredito_do_modelo(png_valido, classificador_falso):
    ferramenta = montar_tool(png_valido, classificador_falso)
    resultado = ferramenta.run(ValidarFotoInput(url_foto=URL_FOTO, categoria="plastico"))
    assert (resultado.status, resultado.pertinente, resultado.confianca) == ("ok", True, 91.23)
    assert classificador_falso.imagens[0].mode == "RGB"
    assert resultado.hash_foto == "0000000000000000"  # foto lisa: nenhum pixel mais claro que o vizinho


def test_tool_transforma_url_recusada_em_falha_tipada(png_valido, classificador_falso):
    ferramenta = montar_tool(png_valido, classificador_falso)
    resultado = ferramenta.run(ValidarFotoInput(url_foto="https://169.254.169.254/x", categoria="vidro"))
    assert (resultado.status, resultado.pertinente) == ("pedido_invalido", None)
    assert classificador_falso.imagens == []


def test_tool_transforma_storage_fora_do_ar_em_falha_tipada(png_valido, classificador_falso):
    ferramenta = montar_tool(png_valido, classificador_falso, status_storage=503)
    resultado = ferramenta.run(ValidarFotoInput(url_foto=URL_FOTO, categoria="vidro"))
    assert (resultado.status, resultado.pertinente) == ("foto_indisponivel", None)
    assert "503" in resultado.mensagem


def test_tool_transforma_arquivo_invalido_em_veredito(classificador_falso):
    ferramenta = montar_tool(b"<html>Access Denied</html>", classificador_falso)
    resultado = ferramenta.run(ValidarFotoInput(url_foto=URL_FOTO, categoria="vidro"))
    assert (resultado.status, resultado.pertinente, resultado.categoria_detectada) == ("ok", False, None)


def test_tool_expoe_seus_schemas_para_o_agente(png_valido, classificador_falso):
    ferramenta = montar_tool(png_valido, classificador_falso)
    assert ferramenta.input_schema is ValidarFotoInput
    assert ferramenta.output_schema is ValidarFotoOutput
    assert ferramenta.tool_description.startswith("Pede a triagem automática")


def test_nome_e_descricao_da_tool_nao_vem_do_ambiente(monkeypatch, png_valido, classificador_falso):
    monkeypatch.setenv("TITLE", "titulo de outro sistema")
    monkeypatch.setenv("DESCRIPTION", "descrição de outro sistema")
    ferramenta = montar_tool(png_valido, classificador_falso)
    assert ferramenta.tool_name == "ValidarFotoInput"
    assert ferramenta.tool_description.startswith("Pede a triagem automática")


def test_config_le_variaveis_de_ambiente(monkeypatch):
    monkeypatch.setenv("HOSTS_PERMITIDOS", " Storage.Exemplo.com , cdn.exemplo.com ,")
    monkeypatch.setenv("LIMIAR_CONFIANCA", "0.65")
    config = ValidarFotoConfig(_env_file=None)
    assert config.conjunto_hosts_permitidos == frozenset({"storage.exemplo.com", "cdn.exemplo.com"})
    assert config.limiar_confianca == 0.65
    assert config.tamanho_maximo_bytes == 10_000_000
    assert config.modelo_clip == "openai/clip-vit-base-patch32"


# ---------- segunda opinião ----------

class OpiniaoFalsa:
    def __init__(self, parecer):
        self.parecer, self.chamadas = parecer, []

    def avaliar(self, imagem, categoria):
        self.chamadas.append(categoria)
        return self.parecer


def tool_em_revisao(png_valido, classificador_falso, parecer):
    classificador_falso.predicao = ("vidro", 0.4)  # entre 0.3 e 0.5: zona de revisão
    opiniao = OpiniaoFalsa(parecer)
    ferramenta = montar_tool(png_valido, classificador_falso, limiar_revisao=0.3)
    ferramenta.segunda_opiniao = opiniao
    return ferramenta, opiniao


def test_segunda_opiniao_afirmativa_aprova_a_foto_em_revisao(png_valido, classificador_falso):
    ferramenta, opiniao = tool_em_revisao(png_valido, classificador_falso, True)
    resultado = ferramenta.run(ValidarFotoInput(url_foto=URL_FOTO, categoria="vidro"))
    assert (resultado.pertinente, resultado.segunda_opiniao) == (True, True)
    assert "segunda análise" in resultado.mensagem
    assert opiniao.chamadas == ["vidro"]


def test_segunda_opiniao_negativa_reprova(png_valido, classificador_falso):
    ferramenta, _ = tool_em_revisao(png_valido, classificador_falso, False)
    resultado = ferramenta.run(ValidarFotoInput(url_foto=URL_FOTO, categoria="vidro"))
    assert (resultado.pertinente, resultado.segunda_opiniao) == (False, True)


def test_sem_parecer_a_foto_continua_em_revisao_humana(png_valido, classificador_falso):
    ferramenta, _ = tool_em_revisao(png_valido, classificador_falso, None)
    resultado = ferramenta.run(ValidarFotoInput(url_foto=URL_FOTO, categoria="vidro"))
    assert (resultado.pertinente, resultado.segunda_opiniao) == (None, False)


def test_segunda_opiniao_so_e_chamada_na_zona_de_revisao(png_valido, classificador_falso):
    ferramenta, opiniao = tool_em_revisao(png_valido, classificador_falso, True)
    classificador_falso.predicao = ("vidro", 0.9)  # aprovada direto
    ferramenta.run(ValidarFotoInput(url_foto=URL_FOTO, categoria="vidro"))
    classificador_falso.predicao = ("plastico", 0.9)  # reprovada direto
    ferramenta.run(ValidarFotoInput(url_foto=URL_FOTO, categoria="vidro"))
    assert opiniao.chamadas == []


# ---------- sinais de foto de tela ----------

def test_tool_avisa_padrao_de_tela_sem_mudar_o_veredito(png_de_tela, classificador_falso):
    resultado = montar_tool(png_de_tela, classificador_falso).run(ValidarFotoInput(url_foto=URL_FOTO, categoria="plastico"))
    assert "padrao_de_tela" in resultado.sinais
    assert resultado.pertinente is True  # o sinal só informa


def test_tool_sem_indicios_devolve_so_o_que_existe(png_valido, classificador_falso):
    resultado = montar_tool(png_valido, classificador_falso).run(ValidarFotoInput(url_foto=URL_FOTO, categoria="plastico"))
    assert resultado.sinais == ["sem_exif"]  # PNG sintético: sem metadados, e pequeno demais para o teste de moiré


# ---------- limiares ----------

def test_limiar_da_categoria_vence_o_global(png_valido, classificador_falso):
    classificador_falso.predicao = ("vidro", 0.6)
    pedido = ValidarFotoInput(url_foto=URL_FOTO, categoria="vidro")
    assert montar_tool(png_valido, classificador_falso).run(pedido).pertinente is True  # global 0.5
    exigente = montar_tool(png_valido, classificador_falso, limiares_por_categoria={"Vidro": 0.7})
    assert exigente.run(pedido).pertinente is False


def test_zona_de_revisao_vem_da_config(png_valido, classificador_falso):
    classificador_falso.predicao = ("vidro", 0.4)
    pedido = ValidarFotoInput(url_foto=URL_FOTO, categoria="vidro")
    assert montar_tool(png_valido, classificador_falso).run(pedido).pertinente is False
    com_revisao = montar_tool(png_valido, classificador_falso, limiar_revisao=0.3)
    assert com_revisao.run(pedido).pertinente is None


def test_tool_devolve_as_alternativas_do_ranking(png_valido, classificador_falso):
    classificador_falso.predicao = ("vidro", 0.6)
    classificador_falso.outros = [("plastico", 0.3), ("metal", 0.1)]
    resultado = montar_tool(png_valido, classificador_falso).run(ValidarFotoInput(url_foto=URL_FOTO, categoria="vidro"))
    assert [(a.categoria, a.confianca) for a in resultado.alternativas] == [("plastico", 30.0), ("metal", 10.0)]


def test_config_le_limiares_por_categoria_do_ambiente(monkeypatch):
    monkeypatch.setenv("HOSTS_PERMITIDOS", "storage.exemplo.com")
    monkeypatch.setenv("LIMIARES_POR_CATEGORIA", '{"Vidro": 0.7}')
    monkeypatch.setenv("LIMIAR_REVISAO", "0.3")
    config = ValidarFotoConfig(_env_file=None)
    assert config.limiar_para("vidro") == 0.7
    assert config.limiar_para("papel") == 0.5
    assert config.limiar_revisao == 0.3


@pytest.mark.parametrize("invalido", [{"eletronico": 0.5}, {"vidro": 1.5}])
def test_config_recusa_limiar_de_categoria_invalido(invalido):
    with pytest.raises(ValidationError):
        ValidarFotoConfig(_env_file=None, hosts_permitidos="x.com", limiares_por_categoria=invalido)


# ---------- escolha do classificador ----------

class ClipFalso:
    """Substitui o ClassificadorClip real (que baixaria o modelo): só precisa de embedding e ranking."""

    def __init__(self, nome_modelo):
        self.nome_modelo = nome_modelo

    def embedding(self, imagem):
        return np.zeros(4)

    def ranking(self, imagem):
        return [("vidro", 0.7)]


def test_sem_cabeca_a_tool_usa_o_clip_zero_shot(monkeypatch):
    monkeypatch.setattr("src.tools.validar_foto_tool.ClassificadorClip", ClipFalso)
    ferramenta = ValidarFotoTool(ValidarFotoConfig(_env_file=None, hosts_permitidos="x.com", modelo_clip="outro/clip"))
    assert isinstance(ferramenta.classificador, ClipFalso)
    assert ferramenta.classificador.nome_modelo == "outro/clip"


def test_com_cabeca_a_tool_classifica_pelos_embeddings(monkeypatch, tmp_path):
    monkeypatch.setattr("src.tools.validar_foto_tool.ClassificadorClip", ClipFalso)
    rng = np.random.default_rng(0)
    treinar_cabeca(rng.normal(0, 1, (20, 4)), ["vidro", "metal"] * 10, modelo=MODELO_PADRAO).salvar(tmp_path / "cabeca.npz")
    config = ValidarFotoConfig(_env_file=None, hosts_permitidos="x.com", cabeca_treinada=str(tmp_path / "cabeca.npz"))
    assert isinstance(ValidarFotoTool(config).classificador, ClassificadorCabeca)


def test_cabeca_treinada_com_outro_clip_e_recusada_na_subida(monkeypatch, tmp_path):
    monkeypatch.setattr("src.tools.validar_foto_tool.ClassificadorClip", ClipFalso)
    rng = np.random.default_rng(0)
    treinar_cabeca(rng.normal(0, 1, (20, 4)), ["vidro", "metal"] * 10, modelo="outro/clip").salvar(tmp_path / "cabeca.npz")
    config = ValidarFotoConfig(_env_file=None, hosts_permitidos="x.com", cabeca_treinada=str(tmp_path / "cabeca.npz"))
    with pytest.raises(ValueError, match="outro/clip"):  # evita probabilidades sem sentido com embeddings de outro modelo
        ValidarFotoTool(config)
