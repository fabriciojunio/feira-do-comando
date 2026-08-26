package br.com.feira.contratos;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Abre a saga. Publicado pelo servico de pedidos assim que o pedido e aceito. */
public record PedidoCriado(
        UUID id,
        UUID correlacao,
        Instant ocorridoEm,
        int versao,
        String clienteId,
        List<ItemDoPedido> itens,
        Dinheiro total) implements Evento {

    public static PedidoCriado de(UUID pedidoId, String clienteId, List<ItemDoPedido> itens,
                                  Dinheiro total, Instant agora) {
        return new PedidoCriado(UUID.randomUUID(), pedidoId, agora, Evento.VERSAO_ATUAL,
                clienteId, List.copyOf(itens), total);
    }
}
