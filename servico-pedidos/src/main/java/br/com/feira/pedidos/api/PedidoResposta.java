package br.com.feira.pedidos.api;

import br.com.feira.pedidos.dominio.ItemPedido;
import br.com.feira.pedidos.dominio.Pedido;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * O pedido do jeito que o cliente ve.
 *
 * A entidade nao vira JSON direto. Aqui isso nao e cerimonia: {@code Pedido}
 * carrega {@code reservaId}, {@code pagamentoId} e a versao da trava otimista,
 * que sao detalhes internos. Expor a entidade faz cada campo novo do dominio
 * vazar para o contrato publico sem ninguem decidir.
 */
public record PedidoResposta(
        UUID id,
        String status,
        String situacao,
        List<Item> itens,
        long totalCentavos,
        String total,
        String motivoDoCancelamento,
        String detalheDoCancelamento,
        Instant criadoEm,
        Instant atualizadoEm) {

    public record Item(String sku, String descricao, int quantidade,
                       long precoUnitarioCentavos, String precoUnitario, String subtotal) {

        static Item de(ItemPedido item) {
            return new Item(item.sku(), item.descricao(), item.quantidade(),
                    item.precoUnitario().centavos(), item.precoUnitario().formatado(),
                    item.subtotal().formatado());
        }
    }

    public static PedidoResposta de(Pedido pedido) {
        return new PedidoResposta(
                pedido.id(),
                pedido.status().name(),
                situacaoLegivel(pedido),
                pedido.itens().stream().map(Item::de).toList(),
                pedido.total().centavos(),
                pedido.total().formatado(),
                pedido.motivoDoCancelamento() == null ? null : pedido.motivoDoCancelamento().name(),
                pedido.detalheDoCancelamento(),
                pedido.criadoEm(),
                pedido.atualizadoEm());
    }

    /**
     * Texto para a tela. O nome do estado interno nao serve: "ESTOQUE_RESERVADO"
     * nao diz nada para quem comprou, e "CANCELANDO" assusta sem explicar.
     */
    private static String situacaoLegivel(Pedido pedido) {
        return switch (pedido.status()) {
            case RECEBIDO -> "Recebemos seu pedido e estamos separando os itens";
            case ESTOQUE_RESERVADO -> "Itens separados, confirmando o pagamento";
            case CANCELANDO -> "Estamos cancelando seu pedido";
            case CONFIRMADO -> "Pedido confirmado";
            case CANCELADO -> "Pedido cancelado";
        };
    }
}
