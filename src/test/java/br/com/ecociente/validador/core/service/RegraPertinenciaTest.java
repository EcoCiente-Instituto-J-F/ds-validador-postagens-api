package br.com.ecociente.validador.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import br.com.ecociente.validador.core.domain.ClasseProbabilidade;
import br.com.ecociente.validador.core.domain.StatusTriagem;

class RegraPertinenciaTest {

  @Test
  void aprovaQuandoClasseCertaComConfiancaSuficiente() {
    var veredito = RegraPertinencia.decidir("vidro",
        List.of(new ClasseProbabilidade("vidro", 0.80), new ClasseProbabilidade("metal", 0.10)), 0.5, null);

    assertThat(veredito.status()).isEqualTo(StatusTriagem.OK);
    assertThat(veredito.pertinente()).isTrue();
    assertThat(veredito.confianca()).isEqualTo(80.0);
    assertThat(veredito.alternativas()).hasSize(1);
    assertThat(veredito.mensagem()).isEqualTo("Foto compatível com vidro.");
  }

  @Test
  void recusaOutraCategoriaComMensagemNoPadraoDoServico() {
    var veredito = RegraPertinencia.decidir("plastico",
        List.of(new ClasseProbabilidade("vidro", 0.9)), 0.5, null);

    assertThat(veredito.pertinente()).isFalse();
    assertThat(veredito.categoriaDetectada()).isEqualTo("vidro");
    assertThat(veredito.mensagem()).isEqualTo("A foto parece conter vidro, não plástico.");
  }

  @Test
  void recusaNaoResiduo() {
    var veredito = RegraPertinencia.decidir("papel",
        List.of(new ClasseProbabilidade("nao_residuo", 0.9)), 0.5, null);

    assertThat(veredito.pertinente()).isFalse();
    assertThat(veredito.mensagem()).isEqualTo("A foto não parece mostrar um resíduo.");
  }

  @Test
  void zonaDeRevisaoDeixaPertinenteNulo() {
    var veredito = RegraPertinencia.decidir("metal",
        List.of(new ClasseProbabilidade("metal", 0.4)), 0.5, 0.3);

    assertThat(veredito.pertinente()).isNull();
    assertThat(veredito.mensagem()).contains("vai para revisão");
  }

  @Test
  void abaixoDoLimiarSemRevisaoRecusa() {
    var veredito = RegraPertinencia.decidir("metal",
        List.of(new ClasseProbabilidade("metal", 0.4)), 0.5, null);

    assertThat(veredito.pertinente()).isFalse();
    assertThat(veredito.mensagem()).endsWith("Tente de mais perto e com boa luz.");
  }
}
