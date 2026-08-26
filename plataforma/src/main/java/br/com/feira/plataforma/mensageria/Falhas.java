package br.com.feira.plataforma.mensageria;

/**
 * As duas falhas que a politica de consumo precisa distinguir.
 *
 * A diferenca entre elas decide se o Kafka tenta de novo ou manda direto para
 * a fila de mensagens mortas, e errar essa decisao custa caro nos dois
 * sentidos: repetir o que nunca vai dar certo trava a particao, e desistir do
 * que era temporario perde um pedido bom.
 */
public final class Falhas {

    private Falhas() {}

    /**
     * A mensagem nao pode ser lida. Tentar de novo nao muda nada: o JSON vai
     * continuar quebrado. Vai direto para a fila de mensagens mortas.
     */
    public static class EventoIlegivel extends RuntimeException {

        public EventoIlegivel(String topico, String chave, Throwable causa) {
            super("evento ilegivel em " + topico + " (chave " + chave + ")", causa);
        }
    }

    /**
     * O dado que o evento referencia ainda nao chegou nesta replica.
     *
     * Acontece de verdade em sistema distribuido: o servico de estoque publica
     * a reserva antes de a transacao do servico de pedidos ter sido enxergada
     * aqui. Nao e defeito, e questao de tempo, e a resposta certa e repetir com
     * recuo.
     */
    public static class AguardandoDadoAnterior extends RuntimeException {

        public AguardandoDadoAnterior(String descricao) {
            super(descricao + " ainda nao encontrado, o evento sera reentregue");
        }
    }
}
