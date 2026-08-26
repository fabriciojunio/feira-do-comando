package br.com.feira.plataforma.mensageria;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;

/**
 * Politica de consumo, num lugar so.
 *
 * <h2>Repeticao com recuo exponencial</h2>
 * Falha de rede e indisponibilidade momentanea se resolvem sozinhas em
 * segundos. Repetir na hora, sem espera, transforma um banco lento em uma
 * tempestade de tentativas que impede o banco de se recuperar.
 *
 * <h2>Fila de mensagens mortas</h2>
 * Depois das tentativas, a mensagem vai para {@code <topico>.dlq} e o consumo
 * segue. Sem isso, uma unica mensagem defeituosa trava a particao e todos os
 * pedidos atras dela param.
 *
 * <h2>O que nao se repete</h2>
 * Mensagem que nao desserializa vai direto para a fila de mortas. Tentar dez
 * vezes desserializar o mesmo JSON quebrado nao muda o resultado e so atrasa
 * quem esta na fila.
 *
 * <h2>Concorrencia</h2>
 * Um consumidor por particao. Mais threads que particoes nao aumenta o
 * paralelismo, so cria thread parada.
 */
@Configuration
public class ConfiguracaoDoKafka {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracaoDoKafka.class);

    @Bean
    public ConsumerFactory<String, String> fabricaDeConsumo(KafkaProperties propriedades) {
        var config = new HashMap<>(propriedades.buildConsumerProperties(null));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // Confirmacao manual pelo container, depois do metodo retornar sem
        // erro. Com auto-commit, uma falha no meio do processamento perde a
        // mensagem, porque o offset ja tinha avancado.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // Lote pequeno: o container so confirma depois de processar tudo, e um
        // lote grande significa reprocessar muita coisa quando algo falha.
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 20);

        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> fabricaDeConsumidores(
            ConsumerFactory<String, String> fabricaDeConsumo,
            KafkaTemplate<String, String> kafka,
            @Value("${feira.kafka.concorrencia:3}") int concorrencia) {

        var fabrica = new ConcurrentKafkaListenerContainerFactory<String, String>();
        fabrica.setConsumerFactory(fabricaDeConsumo);
        fabrica.setConcurrency(concorrencia);
        fabrica.setCommonErrorHandler(tratadorDeErro(kafka));
        return fabrica;
    }

    private DefaultErrorHandler tratadorDeErro(KafkaTemplate<String, String> kafka) {
        var recuperador = new DeadLetterPublishingRecoverer(kafka,
                (registro, excecao) -> new org.apache.kafka.common.TopicPartition(
                        registro.topic() + ".dlq", registro.partition()));

        // 500ms, 1s, 2s, 4s, teto de 10s, ate 30 segundos no total.
        var recuo = new ExponentialBackOff(500L, 2.0);
        recuo.setMaxElapsedTime(30_000L);
        recuo.setMaxInterval(10_000L);

        var tratador = new DefaultErrorHandler(recuperador, recuo);

        // Nao adianta repetir: o problema esta na mensagem, nao no ambiente.
        tratador.addNotRetryableExceptions(
                Falhas.EventoIlegivel.class,
                IllegalArgumentException.class);

        // Adianta repetir: o dado referenciado ainda nao chegou nesta replica.
        tratador.addRetryableExceptions(Falhas.AguardandoDadoAnterior.class);

        tratador.setRetryListeners((registro, excecao, tentativa) ->
                log.warn("Tentativa {} para a mensagem em {}-{} offset {}: {}",
                        tentativa, registro.topic(), registro.partition(), registro.offset(),
                        excecao.getMessage()));

        return tratador;
    }
}
