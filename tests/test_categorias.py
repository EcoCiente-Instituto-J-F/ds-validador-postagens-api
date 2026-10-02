from src.categorias import CATEGORIAS, NAO_RESIDUO, TODAS_AS_CLASSES, normalizar_categoria


def test_normaliza_nome_vindo_do_banco():
    assert normalizar_categoria("Plástico") == "plastico"
    assert normalizar_categoria("  ORGÂNICO ") == "organico"
    assert normalizar_categoria("Papel   Papelão") == "papel_papelao"


def test_catalogo_tem_as_categorias_do_app():
    assert set(CATEGORIAS) == {"papel", "plastico", "vidro", "metal", "organico"}


def test_chave_do_catalogo_e_o_proprio_slug_normalizado():
    for slug, classe in CATEGORIAS.items():
        assert classe.slug == slug == normalizar_categoria(slug)


def test_nao_residuo_e_classe_do_modelo_mas_nao_categoria_aceita():
    assert NAO_RESIDUO.slug not in CATEGORIAS
    assert NAO_RESIDUO in TODAS_AS_CLASSES


def test_prompts_nao_se_repetem_entre_classes():
    prompts = [prompt for classe in TODAS_AS_CLASSES for prompt in classe.prompts]
    assert len(prompts) == len(set(prompts))
