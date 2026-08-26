package br.com.feira.pedidos.dominio;

import java.util.Set;

/**
 * Estados possiveis de um pedido.
 *
 * A saga vive aqui: cada estado sabe para quais outros pode ir. Deixar isso
 * implicito, espalhado por ifs no servico, e como se acaba com um pedido
 * confirmado voltando para "aguardando pagamento" porque uma mensagem chegou
 * fora de ordem.
 */
public enum StatusDoPedido {

    /** Aceito e aguardando a resposta do estoque. */
    RECEBIDO,

    /** Estoque separou as unidades. Aguardando a cobranca. */
    ESTOQUE_RESERVADO,

    /** Pagamento recusado ou cancelamento pedido: compensacao em andamento. */
    CANCELANDO,

    /** Fim feliz. */
    CONFIRMADO,

    /** Fim infeliz, com toda compensacao ja disparada. */
    CANCELADO;

    static {
        RECEBIDO.proximos = Set.of(ESTOQUE_RESERVADO, CANCELADO, CANCELANDO);
        ESTOQUE_RESERVADO.proximos = Set.of(CONFIRMADO, CANCELANDO);
        CANCELANDO.proximos = Set.of(CANCELADO);
        CONFIRMADO.proximos = Set.of();
        CANCELADO.proximos = Set.of();
    }

    private Set<StatusDoPedido> proximos;

    public boolean podeIrPara(StatusDoPedido destino) {
        return proximos.contains(destino);
    }

    /** Estado final nao muda mais, aconteca o que acontecer na fila. */
    public boolean terminal() {
        return proximos.isEmpty();
    }
}
