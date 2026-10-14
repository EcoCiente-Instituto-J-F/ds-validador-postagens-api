package br.com.ecociente.validador.core.imagem;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

class ReamostragemTest {

  @Test
  void mesmoTamanhoDevolveOsMesmosValores() {
    int[] canal = {0, 50, 100, 150, 200, 250};

    int[] saida = Reamostragem.redimensionar(canal, 3, 2, 3, 2, Reamostragem.Nucleo.LANCZOS);

    assertThat(saida).containsExactly(canal);
  }

  @Test
  void canalConstanteContinuaConstanteAoReduzir() {
    int[] canal = new int[100 * 80];
    Arrays.fill(canal, 123);

    int[] saida = Reamostragem.redimensionar(canal, 100, 80, 9, 8, Reamostragem.Nucleo.LANCZOS);

    assertThat(saida).hasSize(72).containsOnly(123);
  }

  @Test
  void bicubicaTambemPreservaConstante() {
    int[] canal = new int[300 * 300];
    Arrays.fill(canal, 200);

    int[] saida = Reamostragem.redimensionar(canal, 300, 300, 224, 224, Reamostragem.Nucleo.BICUBICA);

    assertThat(saida).hasSize(224 * 224).containsOnly(200);
  }

  @Test
  void luminanciaSeguePesosDoPillow() {
    assertThat(Luminancia.de(255, 255, 255)).isEqualTo(255);
    assertThat(Luminancia.de(0, 0, 0)).isZero();
    assertThat(Luminancia.de(255, 0, 0)).isEqualTo(76); // 0.299 * 255
  }
}
