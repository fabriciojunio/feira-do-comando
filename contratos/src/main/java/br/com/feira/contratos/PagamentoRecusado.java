package br.com.feira.contratos;

import java.time.Instant;
import java.util.UUID;

/**
 * Cobranca negada.
 *
 * O motivo vem em codigo estavel e nao em texto livre: e ele que decide se a
 * saga tenta de novo, avisa o cliente ou cancela direto. Texto de gateway muda
 * sem aviso e nao serve para ramificar logica.
 */
public record PagamentoRecusado(
        UUID id,
        UUID correlacao,
        Instant ocorridoEm,
        int versao,
        UUID pagamentoId,
        Motivo motivo) implements Evento {

    public enum Motivo {
        /** Vale nova tentativa: instabilidade momentanea do emissor. */
        FALHA_TEMPORARIA,
        /** Nao vale nova tentativa. */
        SALDO_INSUFICIENTE,
        CARTAO_INVALIDO,
        SUSPEITA_DE_FRAUDE;

        public boolean permiteNovaTentativa() {
            return this == FALHA_TEMPORARIA;
        }
    }

    public static PagamentoRecusado de(UUID pedidoId, UUID pagamentoId, Motivo motivo,
                                       Instant agora) {
        return new PagamentoRecusado(UUID.randomUUID(), pedidoId, agora, Evento.VERSAO_ATUAL,
                pagamentoId, motivo);
    }
}
