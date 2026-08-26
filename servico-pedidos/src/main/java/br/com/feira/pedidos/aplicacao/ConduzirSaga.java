package br.com.feira.pedidos.aplicacao;

import br.com.feira.contratos.Evento;
import br.com.feira.contratos.PedidoCancelado;
import br.com.feira.contratos.PedidoConfirmado;
import br.com.feira.contratos.Topicos;
import br.com.feira.pedidos.dominio.Decisao;
import br.com.feira.pedidos.dominio.Pedido;
import br.com.feira.pedidos.aplicacao.portas.Portas;
import br.com.feira.plataforma.inbox.RegistroDeEntrada;
import br.com.feira.plataforma.mensageria.Falhas;
import br.com.feira.plataforma.outbox.RegistroDeSaida;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Aplica um evento ao pedido e grava o que a saga decidiu.
 *
 * Tudo acontece dentro de uma transacao so: a checagem de idempotencia, a
 * mudanca de estado do pedido, o registro do evento consumido e os eventos
 * produzidos no outbox. Se qualquer parte falhar, nada acontece e o Kafka
 * reentrega. Quebrar isso em varias transacoes reintroduz exatamente o
 * problema que o outbox resolve.
 */
@Service
public class ConduzirSaga {

    private static final Logger log = LoggerFactory.getLogger(ConduzirSaga.class);

    private final Portas.DePedidos pedidos;
    private final RegistroDeEntrada entrada;
    private final RegistroDeSaida saida;
    private final MeterRegistry metricas;
    private final Clock relogio;

    public ConduzirSaga(Portas.DePedidos pedidos, RegistroDeEntrada entrada, RegistroDeSaida saida,
                        MeterRegistry metricas, Clock relogio) {
        this.pedidos = pedidos;
        this.entrada = entrada;
        this.saida = saida;
        this.metricas = metricas;
        this.relogio = relogio;
    }

    @Transactional
    public Resultado aplicar(Evento evento) {
        if (entrada.jaProcessou(evento)) {
            metricas.counter("feira.saga.eventos_repetidos", "tipo", evento.nome()).increment();
            return Resultado.REPETIDO;
        }

        UUID pedidoId = evento.correlacao();
        var achado = pedidos.porId(pedidoId);

        if (achado.isEmpty()) {
            // Acontece de verdade: o evento de outro servico pode chegar antes
            // de a transacao deste ter sido enxergada por esta replica. Lancar
            // faz o Kafka reentregar, que e o comportamento desejado.
            throw new Falhas.AguardandoDadoAnterior("pedido " + pedidoId);
        }

        Pedido pedido = achado.get();
        Decisao decisao = pedido.processar(evento, relogio.instant());

        entrada.registrar(evento, decisao.observacao());

        if (!decisao.mudouDeEstado()) {
            log.debug("Evento {} nao alterou o pedido {}: {}",
                    evento.nome(), pedidoId, decisao.observacao());
            return Resultado.SEM_EFEITO;
        }

        pedidos.salvar(pedido);
        publicar(decisao);

        metricas.counter("feira.saga.transicoes",
                "evento", evento.nome(),
                "status", pedido.status().name()).increment();

        log.info("Pedido {} agora em {}: {}", pedidoId, pedido.status(), decisao.observacao());
        return Resultado.APLICADO;
    }

    private void publicar(Decisao decisao) {
        for (Evento produzido : decisao.eventosASeguir()) {
            saida.gravar(topicoDe(produzido), produzido);
        }
    }

    /**
     * Cada evento vai para o topico do servico que precisa reagir a ele, e nao
     * para um topico unico. Assim o servico de estoque assina so o que lhe diz
     * respeito e nao paga o custo de filtrar o que nao usa.
     */
    private static String topicoDe(Evento evento) {
        return switch (evento) {
            case PedidoConfirmado ignored -> Topicos.PEDIDOS;
            case PedidoCancelado ignored -> Topicos.PEDIDOS;
            default -> Topicos.PEDIDOS;
        };
    }

    public enum Resultado {
        /** Estado mudou e eventos foram gravados. */
        APLICADO,
        /** Evento ja tinha sido processado antes. */
        REPETIDO,
        /** Evento chegou, foi registrado, e nao cabia no estado atual. */
        SEM_EFEITO
    }

}
