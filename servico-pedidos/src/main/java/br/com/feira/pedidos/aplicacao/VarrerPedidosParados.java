package br.com.feira.pedidos.aplicacao;

import br.com.feira.contratos.Evento;
import br.com.feira.contratos.Topicos;
import br.com.feira.pedidos.dominio.Pedido;
import br.com.feira.pedidos.dominio.StatusDoPedido;
import br.com.feira.pedidos.aplicacao.portas.Portas;
import br.com.feira.plataforma.outbox.RegistroDeSaida;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

/**
 * Fecha saga que ficou sem resposta.
 *
 * Saga sem prazo e o defeito mais caro deste tipo de arquitetura: um servico
 * fica mudo, o pedido para em "aguardando pagamento" e o estoque continua
 * reservado. Multiplicado por alguns milhares de pedidos, o catalogo inteiro
 * fica indisponivel sem que nada esteja quebrado de forma visivel.
 */
@Service
public class VarrerPedidosParados {

    private static final Logger log = LoggerFactory.getLogger(VarrerPedidosParados.class);

    private static final List<StatusDoPedido> EM_ABERTO = List.of(
            StatusDoPedido.RECEBIDO,
            StatusDoPedido.ESTOQUE_RESERVADO,
            StatusDoPedido.CANCELANDO);

    private final Portas.DePedidos pedidos;
    private final RegistroDeSaida saida;
    private final MeterRegistry metricas;
    private final Clock relogio;

    public VarrerPedidosParados(Portas.DePedidos pedidos, RegistroDeSaida saida,
                                MeterRegistry metricas, Clock relogio) {
        this.pedidos = pedidos;
        this.saida = saida;
        this.metricas = metricas;
        this.relogio = relogio;
    }

    @Transactional
    public int executar() {
        var agora = relogio.instant();
        var parados = pedidos.emAbertoDesde(EM_ABERTO, agora.minus(Pedido.PRAZO_DA_SAGA));

        int afetados = 0;
        for (Pedido pedido : parados) {
            var decisao = pedido.verificarPrazo(agora);
            if (!decisao.mudouDeEstado()) {
                continue;
            }
            pedidos.salvar(pedido);
            for (Evento evento : decisao.eventosASeguir()) {
                saida.gravar(Topicos.PEDIDOS, evento);
            }
            afetados++;
            log.warn("Pedido {} passou do prazo da saga e foi para {}",
                    pedido.id(), pedido.status());
        }

        if (afetados > 0) {
            metricas.counter("feira.saga.prazo_esgotado").increment(afetados);
        }
        return afetados;
    }
}
