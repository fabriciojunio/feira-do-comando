package br.com.feira.contratos;

/**
 * Nomes dos topicos.
 *
 * Um topico por servico produtor, e nao um por tipo de evento. Isso preserva a
 * ordem entre eventos do mesmo pedido, porque a chave de particao e o id do
 * pedido: dois eventos do mesmo pedido caem sempre na mesma particao e chegam
 * na ordem em que foram publicados. Espalhar por tipo daria mais topicos e
 * nenhuma garantia de ordem entre eles.
 */
public final class Topicos {

    private Topicos() {}

    public static final String PEDIDOS = "feira.pedidos";
    public static final String ESTOQUE = "feira.estoque";
    public static final String PAGAMENTOS = "feira.pagamentos";

    /**
     * Sufixo da fila de mensagens mortas. Uma por topico e por grupo de
     * consumidor, para que um consumidor com defeito nao contamine a analise
     * dos outros.
     */
    public static final String SUFIXO_DLQ = ".dlq";

    public static String dlqDe(String topico) {
        return topico + SUFIXO_DLQ;
    }
}
