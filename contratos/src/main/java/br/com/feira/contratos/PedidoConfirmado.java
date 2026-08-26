package br.com.feira.contratos;

import java.time.Instant;
import java.util.UUID;

/** Fim feliz da saga: estoque reservado e pagamento aprovado. */
public record PedidoConfirmado(
        UUID id,
        UUID correlacao,
        Instant ocorridoEm,
        int versao,
        Dinheiro total) implements Evento {

    public static PedidoConfirmado de(UUID pedidoId, Dinheiro total, Instant agora) {
        return new PedidoConfirmado(UUID.randomUUID(), pedidoId, agora, Evento.VERSAO_ATUAL, total);
    }
}
