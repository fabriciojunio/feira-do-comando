package br.com.feira.plataforma.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Le o outbox e publica no Kafka.
 *
 * <h2>Ordem das operacoes</h2>
 * Publica primeiro, marca depois. O contrario perderia mensagem se o processo
 * caisse entre marcar e publicar. Nesta ordem, uma queda no meio faz a
 * mensagem ser publicada de novo na proxima rodada, o que e aceitavel porque o
 * consumidor e idempotente.
 *
 * <h2>Envio sincrono, de proposito</h2>
 * {@code get()} no future do Kafka dentro da transacao. Assincrono publicaria
 * mais rapido, mas marcaria como publicada uma mensagem cujo envio ainda podia
 * falhar. Throughput aqui vale menos que nao perder evento de pedido.
 */
@Component
public class PublicadorDoOutbox {

    private static final Logger log = LoggerFactory.getLogger(PublicadorDoOutbox.class);

    /** Lote pequeno: mantem a transacao curta e o lock de linha por pouco tempo. */
    private static final int TAMANHO_DO_LOTE = 50;

    private static final Duration ESPERA_DO_ENVIO = Duration.ofSeconds(10);
    private static final Duration RETENCAO_DAS_PUBLICADAS = Duration.ofDays(7);

    private final OutboxRepository repositorio;
    private final KafkaTemplate<String, String> kafka;
    private final MeterRegistry metricas;
    private final Clock relogio;

    public PublicadorDoOutbox(OutboxRepository repositorio, KafkaTemplate<String, String> kafka,
                              MeterRegistry metricas, Clock relogio) {
        this.repositorio = repositorio;
        this.kafka = kafka;
        this.metricas = metricas;
        this.relogio = relogio;
    }

    @Scheduled(fixedDelayString = "${feira.outbox.intervalo-ms:500}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publicarPendentes() {
        var agora = relogio.instant();
        var pendentes = repositorio.proximasParaPublicar(agora,
                MensagemDoOutbox.TENTATIVAS_MAXIMAS, Limit.of(TAMANHO_DO_LOTE));

        for (MensagemDoOutbox mensagem : pendentes) {
            try {
                kafka.send(mensagem.topico(), mensagem.chave(), mensagem.carga())
                        .get(ESPERA_DO_ENVIO.toMillis(), TimeUnit.MILLISECONDS);

                mensagem.marcarPublicada(relogio.instant());
                metricas.counter("feira.outbox.publicadas", "topico", mensagem.topico()).increment();

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mensagem.marcarFalha("envio interrompido", relogio.instant());
                return;

            } catch (Exception e) {
                mensagem.marcarFalha(e.getMessage(), relogio.instant());
                metricas.counter("feira.outbox.falhas", "topico", mensagem.topico()).increment();

                if (mensagem.esgotouTentativas()) {
                    log.error("Mensagem {} do tipo {} esgotou {} tentativas e ficou travada. "
                                    + "Precisa de analise manual.",
                            mensagem.id(), mensagem.tipo(), MensagemDoOutbox.TENTATIVAS_MAXIMAS, e);
                } else {
                    log.warn("Falha ao publicar {} ({}a tentativa): {}",
                            mensagem.id(), mensagem.tentativas(), e.getMessage());
                }
            }
        }
    }

    /**
     * Numero de mensagens paradas. Vira metrica porque outbox crescendo e o
     * primeiro sinal de que o broker esta com problema, e aparece antes de
     * qualquer reclamacao de cliente.
     */
    @Scheduled(fixedDelayString = "${feira.outbox.intervalo-metrica-ms:15000}")
    public void medirPendentes() {
        metricas.gauge("feira.outbox.pendentes", repositorio.countByPublicadaEmIsNull());
    }

    @Scheduled(cron = "${feira.outbox.cron-expurgo:0 30 3 * * *}", zone = "America/Sao_Paulo")
    @Transactional
    public void expurgarPublicadas() {
        int removidas = repositorio.apagarPublicadasAntesDe(
                relogio.instant().minus(RETENCAO_DAS_PUBLICADAS));
        if (removidas > 0) {
            log.info("Outbox: {} mensagens publicadas removidas por retencao", removidas);
        }
    }
}
