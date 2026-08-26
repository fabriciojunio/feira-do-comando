package br.com.feira.pedidos.infraestrutura.mensageria;

import br.com.feira.contratos.Evento;
import br.com.feira.contratos.Topicos;
import br.com.feira.pedidos.aplicacao.ConduzirSaga;
import br.com.feira.plataforma.mensageria.Falhas;
import br.com.feira.plataforma.mensageria.MapeadorDeEventos;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consome o que estoque e pagamentos publicam e entrega para a saga.
 *
 * <h2>O que acontece quando da erro</h2>
 * Nada e tratado aqui de proposito. A politica de repeticao e a fila de
 * mensagens mortas ficam na configuracao do container
 * ({@link ConfiguracaoDoKafka}), num lugar so. Espalhar {@code try/catch} por
 * consumidor e como se acaba com tres politicas de retry diferentes no mesmo
 * servico, nenhuma documentada.
 *
 * <h2>Mensagem ilegivel</h2>
 * JSON que nao desserializa nunca vai desserializar, por mais que se tente. Vai
 * direto para a fila de mensagens mortas, sem repetir: repetir mensagem
 * envenenada e o jeito classico de travar uma particao inteira.
 */
@Component
public class ConsumidorDeEventos {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorDeEventos.class);

    private final ConduzirSaga saga;
    private final MapeadorDeEventos mapeador;
    private final MeterRegistry metricas;

    public ConsumidorDeEventos(ConduzirSaga saga, MapeadorDeEventos mapeador,
                               MeterRegistry metricas) {
        this.saga = saga;
        this.mapeador = mapeador;
        this.metricas = metricas;
    }

    @KafkaListener(
            topics = {Topicos.ESTOQUE, Topicos.PAGAMENTOS},
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "fabricaDeConsumidores")
    public void consumir(@Payload String carga,
                         @Header(name = "kafka_receivedTopic", required = false) String topico,
                         @Header(name = "kafka_receivedMessageKey", required = false) String chave) {

        Evento evento = desserializar(carga, topico, chave);
        var resultado = saga.aplicar(evento);

        metricas.counter("feira.consumidor.eventos",
                "topico", topico == null ? "desconhecido" : topico,
                "tipo", evento.nome(),
                "resultado", resultado.name()).increment();
    }

    private Evento desserializar(String carga, String topico, String chave) {
        try {
            return mapeador.deJson(carga);
        } catch (Exception e) {
            log.error("Mensagem ilegivel no topico {} com chave {}. Vai para a fila de "
                    + "mensagens mortas sem nova tentativa.", topico, chave, e);
            throw new Falhas.EventoIlegivel(topico, chave, e);
        }
    }

}
