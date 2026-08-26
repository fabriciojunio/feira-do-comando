package br.com.feira.contratos;

import java.time.Instant;
import java.util.UUID;

/**
 * Estoque separou as unidades e as segurou para este pedido.
 *
 * Reserva nao e baixa. A baixa so acontece quando o pedido confirma; ate la a
 * unidade fica indisponivel para outros pedidos mas ainda pertence ao estoque.
 * Essa distincao e o que permite compensar sem inventar mercadoria.
 */
public record EstoqueReservado(
        UUID id,
        UUID correlacao,
        Instant ocorridoEm,
        int versao,
        UUID reservaId) implements Evento {

    public static EstoqueReservado de(UUID pedidoId, UUID reservaId, Instant agora) {
        return new EstoqueReservado(UUID.randomUUID(), pedidoId, agora, Evento.VERSAO_ATUAL,
                reservaId);
    }
}
