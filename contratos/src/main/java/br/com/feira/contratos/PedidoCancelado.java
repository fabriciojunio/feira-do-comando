package br.com.feira.contratos;

import java.time.Instant;
import java.util.UUID;

/**
 * Fim infeliz da saga, depois de toda compensacao ter sido disparada.
 *
 * O motivo vem em codigo porque e ele que a tela mostra ao cliente e o que o
 * relatorio agrupa. "Cancelado" sem causa nao ajuda ninguem a reduzir
 * cancelamento.
 */
public record PedidoCancelado(
        UUID id,
        UUID correlacao,
        Instant ocorridoEm,
        int versao,
        Motivo motivo,
        String detalhe) implements Evento {

    public enum Motivo {
        SEM_ESTOQUE,
        PAGAMENTO_RECUSADO,
        PEDIDO_DO_CLIENTE,
        /** A saga passou do prazo esperando resposta de algum passo. */
        TEMPO_ESGOTADO
    }

    public static PedidoCancelado de(UUID pedidoId, Motivo motivo, String detalhe, Instant agora) {
        return new PedidoCancelado(UUID.randomUUID(), pedidoId, agora, Evento.VERSAO_ATUAL,
                motivo, detalhe);
    }
}
