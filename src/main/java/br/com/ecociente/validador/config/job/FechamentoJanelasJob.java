package br.com.ecociente.validador.config.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import br.com.ecociente.validador.core.gateway.PostagemValidacaoGateway;
import br.com.ecociente.validador.core.service.PostagemValidacaoService;

/**
 * Encerra as janelas de validação de 24h vencidas. Substitui o CronJob do serviço antigo: roda dentro da API,
 * com uma transação por janela para que uma falha não trave as demais.
 */
@Component
@ConditionalOnProperty(name = "app.validation.fechamento-habilitado", havingValue = "true", matchIfMissing = true)
public class FechamentoJanelasJob {

  private static final Logger log = LoggerFactory.getLogger(FechamentoJanelasJob.class);

  private final PostagemValidacaoGateway gateway;
  private final PostagemValidacaoService service;
  private final int batchSize;

  public FechamentoJanelasJob(
      PostagemValidacaoGateway gateway,
      PostagemValidacaoService service,
      @Value("${app.validation.batch-size:100}") int batchSize) {

    if (batchSize < 1 || batchSize > 1000) {
      throw new IllegalArgumentException("O tamanho do lote deve estar entre 1 e 1000");
    }
    this.gateway = gateway;
    this.service = service;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${app.validation.delay-ms:60000}")
  public void executar() {
    var postagens = gateway.buscarJanelasVencidas(batchSize);
    int falhas = 0;
    for (Integer postagemId : postagens) {
      try {
        service.encerrarJanela(postagemId);
      } catch (RuntimeException erro) {
        falhas++;
        log.error("falha ao encerrar a janela da postagem {}", postagemId, erro);
      }
    }
    log.info("janelas encerradas: {}, falhas: {}", postagens.size() - falhas, falhas);
  }
}
