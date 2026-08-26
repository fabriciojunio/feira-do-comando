package br.com.feira.contratos;

import java.time.Instant;
import java.util.UUID;

/**
 * Compensacao da reserva: as unidades voltam a ficar disponiveis.
 *
 * Acontece quando o pagamento e recusado ou quando o pedido e cancelado antes
 * da confirmacao. E o passo de compensacao classico de uma saga: nao existe
 * rollback distribuido, existe uma acao inversa que precisa ser idempotente.
 */
public record ReservaLiberada(
        UUID id,
        UUID correlacao,
        Instant ocorridoEm,
        int versao,
        UUID reservaId,
        String motivo) implements Evento {

    public static ReservaLiberada de(UUID pedidoId, UUID reservaId, String motivo, Instant agora) {
        return new ReservaLiberada(UUID.randomUUID(), pedidoId, agora, Evento.VERSAO_ATUAL,
                reservaId, motivo);
    }
}
