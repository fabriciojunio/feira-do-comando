package br.com.feira.contratos;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Estoque nao conseguiu reservar. Carrega quais SKUs faltaram, porque
 * "indisponivel" sozinho obriga o atendimento a abrir o banco para responder o
 * cliente.
 */
public record EstoqueRecusado(
        UUID id,
        UUID correlacao,
        Instant ocorridoEm,
        int versao,
        List<String> skusIndisponiveis,
        String motivo) implements Evento {

    public static EstoqueRecusado de(UUID pedidoId, List<String> skus, String motivo,
                                     Instant agora) {
        return new EstoqueRecusado(UUID.randomUUID(), pedidoId, agora, Evento.VERSAO_ATUAL,
                List.copyOf(skus), motivo);
    }
}
