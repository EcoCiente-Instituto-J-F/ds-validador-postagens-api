package br.com.ecociente.validador.agentes;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;

import br.com.ecociente.validador.core.domain.StatusTriagem;
import br.com.ecociente.validador.core.domain.VeredictoFoto;

/**
 * Contexto do explicador: guarda as últimas 5 triagens para o agente explicar ao morador o que aconteceu com cada foto.
 * Equivale ao context provider do Atomic Agents (TriagensFotosCtx).
 */
public final class ContextoTriagens {

  private static final int MAXIMO = 5;

  private final Deque<VeredictoFoto> triagens = new ArrayDeque<>();

  public synchronized void registrar(VeredictoFoto veredito) {
    if (triagens.size() == MAXIMO) {
      triagens.removeFirst();
    }
    triagens.addLast(veredito);
  }

  public synchronized String texto() {
    if (triagens.isEmpty()) {
      return "Nenhuma foto foi analisada nesta conversa.";
    }
    StringBuilder linhas = new StringBuilder();
    for (VeredictoFoto triagem : triagens) {
      String veredito;
      if (triagem.status() != StatusTriagem.OK) {
        veredito = "não analisada (" + triagem.status().getValor() + ")";
      } else if (triagem.pertinente() == null) {
        veredito = "aguardando revisão humana";
      } else {
        veredito = triagem.pertinente() ? "pertinente" : "não pertinente";
      }
      if (triagem.confianca() != null) {
        veredito += "; o modelo viu " + triagem.categoriaDetectada() + " com "
            + new BigDecimal(String.valueOf(triagem.confianca())).stripTrailingZeros().toPlainString() + "% de confiança";
      }
      linhas.append("- Foto de ").append(triagem.categoriaInformada())
          .append(": ").append(veredito).append(". ").append(triagem.mensagem()).append('\n');
    }
    return linhas.toString().stripTrailing();
  }
}
