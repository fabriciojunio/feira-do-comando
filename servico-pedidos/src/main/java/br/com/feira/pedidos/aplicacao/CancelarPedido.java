package br.com.feira.pedidos.aplicacao;

import br.com.feira.contratos.Evento;
import br.com.feira.contratos.Topicos;
import br.com.feira.pedidos.dominio.Pedido;
import br.com.feira.pedidos.aplicacao.portas.Portas;
import br.com.feira.plataforma.outbox.RegistroDeSaida;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Cancelamento pedido pelo cliente, pela API. */
@Service
public class CancelarPedido {

    private final Portas.DePedidos pedidos;
    private final RegistroDeSaida saida;
    private final Clock relogio;

    public CancelarPedido(Portas.DePedidos pedidos, RegistroDeSaida saida, Clock relogio) {
        this.pedidos = pedidos;
        this.saida = saida;
        this.relogio = relogio;
    }

    /**
     * @param clienteId dono do pedido. Vem do token, nunca do corpo: sem isso,
     *                  qualquer um cancela o pedido de qualquer um sabendo o id.
     */
    @Transactional
    public Pedido executar(UUID pedidoId, String clienteId) {
        var pedido = pedidos.porId(pedidoId)
                .orElseThrow(() -> new NoSuchElementException("pedido nao encontrado"));

        if (!pedido.clienteId().equals(clienteId)) {
            // Mesma resposta de "nao existe". Dizer "existe, mas nao e seu"
            // permite descobrir quais ids existem.
            throw new NoSuchElementException("pedido nao encontrado");
        }

        var decisao = pedido.cancelarAPedidoDoCliente(relogio.instant());
        if (!decisao.mudouDeEstado()) {
            throw new CancelamentoNaoPermitido(pedido.status().name());
        }

        pedidos.salvar(pedido);
        for (Evento evento : decisao.eventosASeguir()) {
            saida.gravar(Topicos.PEDIDOS, evento);
        }
        return pedido;
    }

    public static class CancelamentoNaoPermitido extends RuntimeException {

        private final String status;

        public CancelamentoNaoPermitido(String status) {
            super("pedido em " + status + " nao pode mais ser cancelado");
            this.status = status;
        }

        public String status() {
            return status;
        }
    }
}
