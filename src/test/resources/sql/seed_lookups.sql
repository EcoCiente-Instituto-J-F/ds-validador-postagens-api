INSERT INTO tb_lkp_niveis_confianca (nome_nivel, peso_voto) VALUES ('morador_comum', 1), ('pessoa_confiavel', 3), ('sindico', 3);
INSERT INTO tb_lkp_status_validacoes_postagens (nome_status) VALUES ('aprovada'), ('em_analise'), ('reprovada');
INSERT INTO tb_lkp_tipos_votos_postagens (nome_tipo) VALUES ('aprovar'), ('denunciar');
INSERT INTO tb_lkp_motivos_denuncia (descricao) VALUES ('Nao e lixo reciclavel'), ('Foto nao corresponde a categoria'), ('Foto antiga-reutilizada'), ('Spam-abuso');
INSERT INTO tb_lkp_categorias_residuos (nome_categoria, pontos_base, limite_pontos_diario) VALUES ('Plástico', 10, 25), ('Eletrônicos', 10, NULL);
INSERT INTO tb_lkp_tipos_usuarios (nome_tipo) VALUES ('morador_residencial');
INSERT INTO tb_lkp_tipos_condominios (nome_tipo) VALUES ('residencial');
