package br.com.feira.contratos;

import java.time.Instant;
import java.util.UUID;

/**
 * Compensacao do pagamento.
 *
 * Existe porque a saga pode falhar depois da cobranca aprovada. E o passo mais
 * caro de compensar: dinheiro ja saiu da conta do cliente, e o estorno tem
 * custo e prazo. Por isso a cobranca e o ultimo passo da saga, nao o primeiro.
 */
public record PagamentoEstornado(
        UUID id,
        UUID correlacao,
        Instant ocorridoEm,
        int versao,
        UUID pagamentoId,
        Dinheiro valor,
        String motivo) implements Evento {

    public static PagamentoEstornado de(UUID pedidoId, UUID pagamentoId, Dinheiro valor,
                                        String motivo, Instant agora) {
        return new PagamentoEstornado(UUID.randomUUID(), pedidoId, agora, Evento.VERSAO_ATUAL,
                pagamentoId, valor, motivo);
    }
}
