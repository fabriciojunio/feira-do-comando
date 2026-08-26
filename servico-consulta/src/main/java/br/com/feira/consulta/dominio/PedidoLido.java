package br.com.feira.consulta.dominio;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * O pedido do jeito que a tela precisa ler.
 *
 * <h2>Por que existe</h2>
 * Cada servico tem o proprio banco e nenhum deles responde sozinho "como esta
 * o meu pedido": o estado da saga vive em pedidos, a reserva vive em estoque e
 * a cobranca vive em pagamentos. Ate agora quem juntava isso era o navegador,
 * com tres chamadas. Funciona, mas paga o preco em latencia, e uma das tres
 * cair deixa a tela contando meia historia.
 *
 * <h2>Por que MongoDB e nao mais uma tabela</h2>
 * O que a tela quer e um documento: o pedido com os itens dentro, a cobranca
 * dentro e a linha do tempo dentro. Em tabela isso vira junção de quatro
 * relacoes para montar uma resposta que nunca e consultada em pedaco. Aqui o
 * documento e gravado e lido inteiro, com uma ida ao banco.
 *
 * <p>E a escolha e barata de defender pelo lado oposto: este dado <b>nao e</b>
 * a fonte da verdade. Ele e derivado dos eventos e pode ser jogado fora e
 * reconstruido do topico. Consistencia forte fica com o PostgreSQL de cada
 * servico, que e onde ela e cobrada.
 *
 * <h2>O que este documento nao garante</h2>
 * Consistencia imediata. A projecao acompanha o topico, entao existe uma
 * janela entre o pedido mudar de estado e a leitura refletir. Para acompanhar
 * a saga isso e aceitavel, e a tela ja consulta em ciclo. Para decidir se pode
 * cancelar, quem manda continua sendo o servico de pedidos.
 */
@Document(collection = "pedidos")
public class PedidoLido {

    @Id
    private UUID id;

    @Indexed
    private String clienteId;

    @Indexed
    private String status;

    private List<Item> itens = new ArrayList<>();
    private BigDecimal total;

    private String situacaoDaCobranca;
    private String motivoDaRecusa;
    private String referenciaExterna;

    private String motivoDoCancelamento;
    private String detalheDoCancelamento;

    private Instant criadoEm;
    private Instant atualizadoEm;

    /**
     * Linha do tempo do que aconteceu, na ordem em que a projecao viu.
     *
     * <p>Guardar isso e o que permite a tela explicar o pedido em vez de
     * mostrar um status seco. E e informacao que nenhum dos tres servicos tem
     * sozinho: cada um conhece so o proprio pedaco.
     */
    private List<Marco> linhaDoTempo = new ArrayList<>();

    /**
     * Ultimo evento aplicado, por id.
     *
     * <p>Kafka entrega ao menos uma vez. Sem isto, reprocessar uma particao
     * duplicaria a linha do tempo, e o cliente veria "estoque reservado" duas
     * vezes num pedido que reservou uma.
     */
    private List<UUID> eventosAplicados = new ArrayList<>();

    /**
     * Numero de eventos ja aplicados, usado como versao otimista.
     *
     * <p>Duas particoes entregando eventos do mesmo pedido em paralelo nao
     * acontece, porque a chave de particao e o id do pedido. Mas uma
     * reentrega durante rebalanceamento acontece, e a versao e o que permite
     * detectar escrita concorrente em vez de sobrescrever em silencio.
     */
    private long versao;

    protected PedidoLido() {
    }

    public PedidoLido(UUID id, String clienteId, Instant criadoEm) {
        this.id = id;
        this.clienteId = clienteId;
        this.criadoEm = criadoEm;
        this.atualizadoEm = criadoEm;
    }

    public record Item(String sku, String descricao, int quantidade, BigDecimal precoUnitario) {
    }

    public record Marco(String o_que, String detalhe, Instant quando) {
    }

    /**
     * Aplica um evento, se ele ainda nao foi aplicado.
     *
     * @return verdadeiro quando o documento mudou; falso quando o evento era
     *         repetido e nada foi feito.
     */
    public boolean registrar(UUID idDoEvento, String oQue, String detalhe, Instant quando) {
        if (eventosAplicados.contains(idDoEvento)) {
            return false;
        }
        eventosAplicados.add(idDoEvento);
        linhaDoTempo.add(new Marco(oQue, detalhe, quando));
        atualizadoEm = quando;
        versao++;
        return true;
    }

    public boolean jaAplicou(UUID idDoEvento) {
        return eventosAplicados.contains(idDoEvento);
    }

    // ---- getters e setters usados pelo projetor e pela serializacao ----

    public UUID getId() {
        return id;
    }

    public String getClienteId() {
        return clienteId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<Item> getItens() {
        return itens;
    }

    public void setItens(List<Item> itens) {
        this.itens = itens;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public String getSituacaoDaCobranca() {
        return situacaoDaCobranca;
    }

    public void setSituacaoDaCobranca(String situacaoDaCobranca) {
        this.situacaoDaCobranca = situacaoDaCobranca;
    }

    public String getMotivoDaRecusa() {
        return motivoDaRecusa;
    }

    public void setMotivoDaRecusa(String motivoDaRecusa) {
        this.motivoDaRecusa = motivoDaRecusa;
    }

    public String getReferenciaExterna() {
        return referenciaExterna;
    }

    public void setReferenciaExterna(String referenciaExterna) {
        this.referenciaExterna = referenciaExterna;
    }

    public String getMotivoDoCancelamento() {
        return motivoDoCancelamento;
    }

    public void setMotivoDoCancelamento(String motivoDoCancelamento) {
        this.motivoDoCancelamento = motivoDoCancelamento;
    }

    public String getDetalheDoCancelamento() {
        return detalheDoCancelamento;
    }

    public void setDetalheDoCancelamento(String detalheDoCancelamento) {
        this.detalheDoCancelamento = detalheDoCancelamento;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public Instant getAtualizadoEm() {
        return atualizadoEm;
    }

    public List<Marco> getLinhaDoTempo() {
        return linhaDoTempo;
    }

    public long getVersao() {
        return versao;
    }
}
