package br.com.feira.estoque.infraestrutura.mensageria;

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
import br.com.feira.estoque.aplicacao.EncerrarReserva;
import br.com.feira.estoque.aplicacao.ReservarEstoque;
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
 * Reage ao que o servico de pedidos publica.
 *
 * O {@code switch} sobre a interface selada nao tem {@code default}: quando um
 * evento novo entrar no contrato, esta classe para de compilar e alguem
 * precisa decidir, conscientemente, se o estoque reage ou ignora. Num sistema
 * distribuido, evento novo silenciosamente ignorado e o defeito que so aparece
 * semanas depois, na conciliacao.
 */
@Component
public class ConsumidorDoEstoque {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorDoEstoque.class);

    private final ReservarEstoque reservar;
    private final EncerrarReserva encerrar;
    private final RegistroDeEntrada entrada;
    private final MapeadorDeEventos mapeador;
    private final MeterRegistry metricas;

    public ConsumidorDoEstoque(ReservarEstoque reservar, EncerrarReserva encerrar,
                               RegistroDeEntrada entrada, MapeadorDeEventos mapeador,
                               MeterRegistry metricas) {
        this.reservar = reservar;
        this.encerrar = encerrar;
        this.entrada = entrada;
        this.mapeador = mapeador;
        this.metricas = metricas;
    }

    @KafkaListener(
            topics = Topicos.PEDIDOS,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "fabricaDeConsumidores")
    @Transactional
    public void consumir(@Payload String carga,
                         @Header(name = "kafka_receivedTopic", required = false) String topico,
                         @Header(name = "kafka_receivedMessageKey", required = false) String chave) {

        Evento evento = ler(carga, topico, chave);

        if (entrada.jaProcessou(evento)) {
            metricas.counter("feira.estoque.eventos_repetidos").increment();
            return;
        }

        String resultado = tratar(evento);
        entrada.registrar(evento, resultado);

        metricas.counter("feira.estoque.eventos", "tipo", evento.nome()).increment();
    }

    private String tratar(Evento evento) {
        return switch (evento) {
            case PedidoCriado e -> {
                reservar.executar(e);
                yield "tentativa de reserva";
            }
            case ReservaLiberada e -> {
                encerrar.liberar(e.correlacao(), e.motivo());
                yield "reserva liberada";
            }
            case PedidoConfirmado e -> {
                encerrar.baixar(e.correlacao());
                yield "reserva baixada";
            }
            case PedidoCancelado e -> {
                // Rede de seguranca: se o cancelamento chegou sem a liberacao
                // ter passado por aqui, a mercadoria seria perdida. Liberar de
                // novo e inofensivo porque a reserva ja nao estara ativa.
                encerrar.liberar(e.correlacao(), "pedido cancelado: " + e.motivo());
                yield "reserva liberada no cancelamento";
            }

            // Produzidos por este servico ou dirigidos a outro.
            case EstoqueReservado e -> "produzido por este servico";
            case EstoqueRecusado e -> "produzido por este servico";
            case PagamentoAprovado e -> "nao interessa ao estoque";
            case PagamentoRecusado e -> "nao interessa ao estoque";
            case PagamentoEstornado e -> "nao interessa ao estoque";
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
