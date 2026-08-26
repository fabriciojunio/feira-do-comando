package br.com.feira.contratos;

import java.time.Instant;
import java.util.UUID;

/** Cobranca autorizada. Carrega a referencia da transacao para conciliacao. */
public record PagamentoAprovado(
        UUID id,
        UUID correlacao,
        Instant ocorridoEm,
        int versao,
        UUID pagamentoId,
        String referenciaExterna,
        Dinheiro valor) implements Evento {

    public static PagamentoAprovado de(UUID pedidoId, UUID pagamentoId, String referencia,
                                       Dinheiro valor, Instant agora) {
        return new PagamentoAprovado(UUID.randomUUID(), pedidoId, agora, Evento.VERSAO_ATUAL,
                pagamentoId, referencia, valor);
    }
}
