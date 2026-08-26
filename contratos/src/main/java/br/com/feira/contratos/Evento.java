package br.com.feira.contratos;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope comum de todo evento que trafega entre os servicos.
 *
 * Tres campos existem por motivos operacionais concretos:
 *
 * <ul>
 *   <li>{@code id} identifica a entrega. E ele que o consumidor guarda para
 *       nao processar duas vezes. Kafka entrega ao menos uma vez, entao
 *       reprocessamento nao e excecao, e sim o caso comum depois de qualquer
 *       rebalanceamento de particao.</li>
 *   <li>{@code correlacao} amarra tudo que pertence ao mesmo pedido. Sem ele,
 *       investigar por que um pedido travou vira leitura de log linha a linha
 *       em tres servicos diferentes.</li>
 *   <li>{@code versao} permite mudar o formato sem derrubar consumidor antigo.
 *       Comeca em 1 e so muda quando a mudanca quebra compatibilidade.</li>
 * </ul>
 *
 * A hierarquia e selada de proposito: adicionar um evento novo obriga a
 * atualizar todo {@code switch} que trata eventos, e o compilador cobra. Em um
 * sistema distribuido, evento novo que ninguem trata e falha silenciosa.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "tipo")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PedidoCriado.class, name = "PedidoCriado"),
        @JsonSubTypes.Type(value = EstoqueReservado.class, name = "EstoqueReservado"),
        @JsonSubTypes.Type(value = EstoqueRecusado.class, name = "EstoqueRecusado"),
        @JsonSubTypes.Type(value = ReservaLiberada.class, name = "ReservaLiberada"),
        @JsonSubTypes.Type(value = PagamentoAprovado.class, name = "PagamentoAprovado"),
        @JsonSubTypes.Type(value = PagamentoRecusado.class, name = "PagamentoRecusado"),
        @JsonSubTypes.Type(value = PagamentoEstornado.class, name = "PagamentoEstornado"),
        @JsonSubTypes.Type(value = PedidoConfirmado.class, name = "PedidoConfirmado"),
        @JsonSubTypes.Type(value = PedidoCancelado.class, name = "PedidoCancelado")
})
public sealed interface Evento
        permits PedidoCriado, EstoqueReservado, EstoqueRecusado, ReservaLiberada,
        PagamentoAprovado, PagamentoRecusado, PagamentoEstornado,
        PedidoConfirmado, PedidoCancelado {

    int VERSAO_ATUAL = 1;

    UUID id();

    /** Id do pedido. Serve de chave de particao e de correlacao. */
    UUID correlacao();

    Instant ocorridoEm();

    int versao();

    /**
     * Nome do tipo, como vai no cabecalho da mensagem. Consumidor que ainda
     * nao conhece o tipo consegue registrar e seguir em vez de estourar.
     */
    default String nome() {
        return getClass().getSimpleName();
    }
}
