package br.com.feira.pagamentos.infraestrutura.mensageria;

import br.com.feira.contratos.EstoqueRecusado;
import br.com.feira.contratos.EstoqueReservado;
import br.com.feira.contratos.Evento;
import br.com.feira.contratos.PagamentoAprovado;
import br.com.feira.contratos.PagamentoEstornado;
import br.com.feira.contratos.PagamentoRecusado;
import br.com.feira.contratos.PedidoCancelado;
import br.com.feira.contratos.PedidoConfirmado;
import br.com.feira.contratos.PedidoCriado;
import br.com.feira.contratos.ReservaLiberada;
import br.com.feira.contratos.Topicos;
import br.com.feira.pagamentos.aplicacao.ProcessarCobranca;
import br.com.feira.plataforma.inbox.RegistroDeEntrada;
import br.com.feira.plataforma.mensageria.Falhas;
import br.com.feira.plataforma.mensageria.MapeadorDeEventos;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reage ao pedido e ao estoque.
 *
 * Assina dois topicos porque precisa do valor, que vem no PedidoCriado, e do
 * gatilho, que vem no EstoqueReservado. Cobrar direto no PedidoCriado seria
 * mais simples e estaria errado: o dinheiro sairia antes de existir mercadoria
 * separada, e todo pedido sem estoque viraria um estorno.
 */
@Component
public class ConsumidorDePagamentos {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorDePagamentos.class);

    private final ProcessarCobranca cobranca;
    private final RegistroDeEntrada entrada;
    private final MapeadorDeEventos mapeador;
    private final MeterRegistry metricas;

    public ConsumidorDePagamentos(ProcessarCobranca cobranca, RegistroDeEntrada entrada,
                                  MapeadorDeEventos mapeador, MeterRegistry metricas) {
        this.cobranca = cobranca;
        this.entrada = entrada;
        this.mapeador = mapeador;
        this.metricas = metricas;
    }

    @KafkaListener(
            topics = {Topicos.PEDIDOS, Topicos.ESTOQUE},
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "fabricaDeConsumidores")
    @Transactional
    public void consumir(@Payload String carga,
                         @Header(name = "kafka_receivedTopic", required = false) String topico,
                         @Header(name = "kafka_receivedMessageKey", required = false) String chave) {

        Evento evento = ler(carga, topico, chave);

        if (entrada.jaProcessou(evento)) {
            metricas.counter("feira.pagamentos.eventos_repetidos").increment();
            return;
        }

        String resultado = tratar(evento);
        entrada.registrar(evento, resultado);
        metricas.counter("feira.pagamentos.eventos", "tipo", evento.nome()).increment();
    }

    private String tratar(Evento evento) {
        return switch (evento) {
            case PedidoCriado e -> {
                cobranca.registrar(e);
                yield "cobranca registrada como pendente";
            }
            case EstoqueReservado e -> {
                cobranca.cobrar(e.correlacao());
                yield "cobranca processada";
            }
            case ReservaLiberada e -> {
                cobranca.estornar(e.correlacao(), e.motivo());
                yield "estorno avaliado";
            }
            case PedidoCancelado e -> {
                cobranca.estornar(e.correlacao(), "pedido cancelado: " + e.motivo());
                yield "estorno avaliado no cancelamento";
            }

            case EstoqueRecusado e -> "sem estoque, nada a cobrar";
            case PedidoConfirmado e -> "nada a fazer";
            case PagamentoAprovado e -> "produzido por este servico";
            case PagamentoRecusado e -> "produzido por este servico";
            case PagamentoEstornado e -> "produzido por este servico";
        };
    }

    private Evento ler(String carga, String topico, String chave) {
        try {
            return mapeador.deJson(carga);
        } catch (Exception e) {
            log.error("Evento ilegivel em {} com chave {}", topico, chave, e);
            throw new Falhas.EventoIlegivel(topico, chave, e);
        }
    }
}
