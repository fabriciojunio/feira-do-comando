package br.com.feira.pedidos.dominio;

import br.com.feira.contratos.Dinheiro;
import br.com.feira.contratos.EstoqueRecusado;
import br.com.feira.contratos.EstoqueReservado;
import br.com.feira.contratos.Evento;
import br.com.feira.contratos.ItemDoPedido;
import br.com.feira.contratos.PagamentoAprovado;
import br.com.feira.contratos.PagamentoEstornado;
import br.com.feira.contratos.PagamentoRecusado;
import br.com.feira.contratos.PedidoCancelado;
import br.com.feira.contratos.PedidoConfirmado;
import br.com.feira.contratos.PedidoCriado;
import br.com.feira.contratos.ReservaLiberada;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pedido e, ao mesmo tempo, o agregado de negocio e o orquestrador da saga.
 *
 * <h2>Por que a saga mora aqui</h2>
 * A alternativa comum e um "SagaOrchestrator" separado, com o estado numa
 * tabela propria. Nao foi o caminho escolhido: o estado da saga <em>e</em> o
 * estado do pedido. Separar os dois cria duas fontes de verdade que precisam
 * concordar, e elas eventualmente discordam, normalmente as tres da manha.
 *
 * <h2>O que este agregado nao faz</h2>
 * Nao publica em Kafka e nao grava em banco. Ele recebe um evento e devolve
 * uma {@link Decisao}. Quem executa e a camada de aplicacao, dentro de uma
 * transacao, gravando os eventos no outbox. Isso mantem a maquina de estados
 * testavel sem infraestrutura nenhuma.
 *
 * <h2>Tres situacoes que a fila garante que vao acontecer</h2>
 * <ul>
 *   <li><b>Evento repetido.</b> Kafka entrega ao menos uma vez. Reprocessar
 *       {@code EstoqueReservado} num pedido que ja reservou nao pode reservar
 *       de novo nem cobrar de novo.</li>
 *   <li><b>Evento fora de ordem.</b> Particoes diferentes nao tem ordem entre
 *       si. Um {@code PagamentoAprovado} pode chegar antes do
 *       {@code EstoqueReservado} se o pagamento respondeu rapido demais.</li>
 *   <li><b>Evento atrasado.</b> Depois de o pedido ja ter sido cancelado por
 *       tempo esgotado, a resposta antiga ainda chega.</li>
 * </ul>
 * Nos tres casos a resposta correta e a mesma: ignorar sem erro. Por isso
 * {@link Decisao#ignorar(String)} existe e nao e uma excecao.
 */
@Entity
@Table(name = "pedidos")
public class Pedido {

    /**
     * Tempo maximo que a saga espera por uma resposta antes de desistir.
     *
     * Quinze minutos e uma escolha de negocio, nao tecnica: e o tempo que o
     * cliente aceita a tela dizendo "processando" antes de achar que o site
     * quebrou e comprar em outro lugar. Prazo curto demais cancela pedido bom
     * quando a fila atrasa; longo demais prende estoque que poderia estar
     * vendendo.
     */
    public static final Duration PRAZO_DA_SAGA = Duration.ofMinutes(15);

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "cliente_id", nullable = false, length = 80)
    private String clienteId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "pedido_itens", joinColumns = @JoinColumn(name = "pedido_id"))
    @OrderColumn(name = "posicao")
    private List<ItemPedido> itens = new ArrayList<>();

    @Column(name = "total_centavos", nullable = false)
    private long totalCentavos;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private StatusDoPedido status;

    @Column(name = "reserva_id")
    private UUID reservaId;

    @Column(name = "pagamento_id")
    private UUID pagamentoId;

    @Column(name = "motivo_cancelamento", length = 40)
    @Enumerated(EnumType.STRING)
    private PedidoCancelado.Motivo motivoDoCancelamento;

    @Column(name = "detalhe_cancelamento", length = 300)
    private String detalheDoCancelamento;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    @Column(name = "atualizado_em", nullable = false)
    private Instant atualizadoEm;

    /**
     * Trava otimista. Dois consumidores podem processar eventos do mesmo
     * pedido em paralelo se o rebalanceamento de particao acontecer no momento
     * errado. Sem isto, o segundo sobrescreveria a decisao do primeiro.
     */
    @Version
    @Column(name = "versao", nullable = false)
    private long versao;

    protected Pedido() {
        // exigido pelo JPA
    }

    private Pedido(UUID id, String clienteId, List<ItemPedido> itens, Instant agora) {
        this.id = id;
        this.clienteId = clienteId;
        this.itens = new ArrayList<>(itens);
        this.totalCentavos = itens.stream()
                .map(ItemPedido::subtotal)
                .reduce(Dinheiro.ZERO, Dinheiro::mais)
                .centavos();
        this.status = StatusDoPedido.RECEBIDO;
        this.criadoEm = agora;
        this.atualizadoEm = agora;
    }

    /**
     * Cria o pedido e ja devolve o evento que abre a saga. Criar sem publicar
     * seria criar um pedido que ninguem processa.
     */
    public static Pedido abrir(UUID id, String clienteId, List<ItemDoPedido> itens, Instant agora) {
        if (itens == null || itens.isEmpty()) {
            throw new IllegalArgumentException("pedido precisa de ao menos um item");
        }
        if (clienteId == null || clienteId.isBlank()) {
            throw new IllegalArgumentException("pedido precisa de um cliente");
        }
        var quantidades = new java.util.HashMap<String, Long>();
        for (var item : itens) {
            long total = quantidades.merge(item.sku(), (long) item.quantidade(), Long::sum);
            if (total > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("quantidade total do SKU excede o limite");
            }
        }
        return new Pedido(id, clienteId, itens.stream().map(ItemPedido::de).toList(), agora);
    }

    public PedidoCriado eventoDeAbertura(Instant agora) {
        return PedidoCriado.de(id, clienteId, itens.stream().map(ItemPedido::paraContrato).toList(),
                total(), agora);
    }

    /**
     * Ponto unico de entrada da saga.
     *
     * O {@code switch} sobre a interface selada nao tem {@code default} de
     * proposito: quando um evento novo entrar no contrato, este metodo para de
     * compilar. Em sistema distribuido, evento novo que ninguem trata e uma
     * falha que so aparece em producao, e de madrugada.
     */
    public Decisao processar(Evento evento, Instant agora) {
        if (status.terminal()) {
            return Decisao.ignorar("pedido ja esta em " + status + ", evento "
                    + evento.nome() + " chegou tarde");
        }

        Decisao decisao = switch (evento) {
            case EstoqueReservado e -> aoReservarEstoque(e, agora);
            case EstoqueRecusado e -> aoRecusarEstoque(e, agora);
            case PagamentoAprovado e -> aoAprovarPagamento(e, agora);
            case PagamentoRecusado e -> aoRecusarPagamento(e, agora);
            case ReservaLiberada e -> aoLiberarReserva(e, agora);
            case PagamentoEstornado e -> aoEstornarPagamento(e, agora);

            // Eventos que este servico produz, e portanto nao consome.
            case PedidoCriado e -> Decisao.ignorar("evento produzido por este servico");
            case PedidoConfirmado e -> Decisao.ignorar("evento produzido por este servico");
            case PedidoCancelado e -> Decisao.ignorar("evento produzido por este servico");
        };

        if (decisao.mudouDeEstado()) {
            this.atualizadoEm = agora;
        }
        return decisao;
    }

    private Decisao aoReservarEstoque(EstoqueReservado evento, Instant agora) {
        if (status != StatusDoPedido.RECEBIDO) {
            return Decisao.ignorar("reserva chegou com o pedido em " + status);
        }
        this.reservaId = evento.reservaId();
        this.status = StatusDoPedido.ESTOQUE_RESERVADO;
        // A cobranca e o proximo passo, e e disparada por comando do servico de
        // pagamentos ao ver este pedido em ESTOQUE_RESERVADO. O evento de
        // dominio publicado aqui e o proprio avanco de estado.
        return Decisao.avancar("estoque reservado, seguindo para cobranca");
    }

    private Decisao aoRecusarEstoque(EstoqueRecusado evento, Instant agora) {
        if (status != StatusDoPedido.RECEBIDO) {
            return Decisao.ignorar("recusa de estoque chegou com o pedido em " + status);
        }
        // Nada a compensar: nao houve reserva nem cobranca. Vai direto ao fim.
        this.status = StatusDoPedido.CANCELADO;
        this.motivoDoCancelamento = PedidoCancelado.Motivo.SEM_ESTOQUE;
        this.detalheDoCancelamento = "Sem estoque: " + String.join(", ", evento.skusIndisponiveis());
        return Decisao.avancar("sem estoque, cancelando sem compensacao",
                PedidoCancelado.de(id, PedidoCancelado.Motivo.SEM_ESTOQUE,
                        detalheDoCancelamento, agora));
    }

    private Decisao aoAprovarPagamento(PagamentoAprovado evento, Instant agora) {
        if (status == StatusDoPedido.CANCELANDO) {
            // Corrida real: o pagamento foi aprovado enquanto a saga ja tinha
            // decidido cancelar. O dinheiro precisa voltar.
            this.pagamentoId = evento.pagamentoId();
            return Decisao.avancar("pagamento aprovado durante o cancelamento, estornando",
                    PagamentoEstornado.de(id, evento.pagamentoId(), evento.valor(),
                            "pedido em cancelamento", agora));
        }
        if (status != StatusDoPedido.ESTOQUE_RESERVADO) {
            return Decisao.ignorar("pagamento aprovado chegou com o pedido em " + status);
        }
        this.pagamentoId = evento.pagamentoId();
        this.status = StatusDoPedido.CONFIRMADO;
        return Decisao.avancar("pagamento aprovado, pedido confirmado",
                PedidoConfirmado.de(id, total(), agora));
    }

    private Decisao aoRecusarPagamento(PagamentoRecusado evento, Instant agora) {
        if (status != StatusDoPedido.ESTOQUE_RESERVADO) {
            return Decisao.ignorar("recusa de pagamento chegou com o pedido em " + status);
        }
        this.status = StatusDoPedido.CANCELANDO;
        this.motivoDoCancelamento = PedidoCancelado.Motivo.PAGAMENTO_RECUSADO;
        this.detalheDoCancelamento = evento.motivo().name();
        return Decisao.avancar("pagamento recusado, liberando a reserva",
                ReservaLiberada.de(id, reservaId, "pagamento recusado: " + evento.motivo(), agora));
    }

    private Decisao aoLiberarReserva(ReservaLiberada evento, Instant agora) {
        if (status != StatusDoPedido.CANCELANDO) {
            return Decisao.ignorar("liberacao de reserva chegou com o pedido em " + status);
        }
        this.status = StatusDoPedido.CANCELADO;
        return Decisao.avancar("compensacao concluida, pedido cancelado",
                PedidoCancelado.de(id,
                        motivoDoCancelamento == null
                                ? PedidoCancelado.Motivo.PEDIDO_DO_CLIENTE
                                : motivoDoCancelamento,
                        detalheDoCancelamento, agora));
    }

    private Decisao aoEstornarPagamento(PagamentoEstornado evento, Instant agora) {
        if (status != StatusDoPedido.CANCELANDO) {
            return Decisao.ignorar("estorno chegou com o pedido em " + status);
        }
        // O estorno fecha a compensacao do pagamento; a reserva ainda precisa
        // voltar antes de o pedido virar CANCELADO.
        return Decisao.avancar("estorno confirmado, aguardando liberacao da reserva",
                ReservaLiberada.de(id, reservaId, "estorno concluido", agora));
    }

    /** Cancelamento a pedido do cliente, enquanto ainda da tempo. */
    public Decisao cancelarAPedidoDoCliente(Instant agora) {
        if (status.terminal()) {
            return Decisao.ignorar("pedido ja esta em " + status);
        }
        this.motivoDoCancelamento = PedidoCancelado.Motivo.PEDIDO_DO_CLIENTE;
        this.detalheDoCancelamento = "cancelamento solicitado pelo cliente";
        this.atualizadoEm = agora;

        if (status == StatusDoPedido.RECEBIDO) {
            this.status = StatusDoPedido.CANCELADO;
            return Decisao.avancar("cancelado antes de reservar estoque",
                    PedidoCancelado.de(id, PedidoCancelado.Motivo.PEDIDO_DO_CLIENTE,
                            detalheDoCancelamento, agora));
        }

        this.status = StatusDoPedido.CANCELANDO;
        return Decisao.avancar("cancelando e liberando a reserva",
                ReservaLiberada.de(id, reservaId, "cancelamento do cliente", agora));
    }

    /**
     * Chamado pela varredura de pedidos parados. Saga sem prazo e saga que
     * segura estoque para sempre quando um servico fica mudo.
     */
    public Decisao verificarPrazo(Instant agora) {
        if (status.terminal()) {
            return Decisao.ignorar("pedido ja finalizado");
        }
        if (!atualizadoEm.plus(PRAZO_DA_SAGA).isBefore(agora)) {
            return Decisao.ignorar("dentro do prazo");
        }

        this.motivoDoCancelamento = PedidoCancelado.Motivo.TEMPO_ESGOTADO;
        this.detalheDoCancelamento = "sem resposta apos " + PRAZO_DA_SAGA.toMinutes() + " minutos";
        this.atualizadoEm = agora;

        if (status == StatusDoPedido.RECEBIDO) {
            this.status = StatusDoPedido.CANCELADO;
            return Decisao.avancar("prazo esgotado antes da reserva",
                    PedidoCancelado.de(id, PedidoCancelado.Motivo.TEMPO_ESGOTADO,
                            detalheDoCancelamento, agora));
        }

        this.status = StatusDoPedido.CANCELANDO;
        return Decisao.avancar("prazo esgotado, liberando a reserva",
                ReservaLiberada.de(id, reservaId, "prazo da saga esgotado", agora));
    }

    public Dinheiro total() {
        return new Dinheiro(totalCentavos);
    }

    public UUID id() {
        return id;
    }

    public String clienteId() {
        return clienteId;
    }

    public List<ItemPedido> itens() {
        return List.copyOf(itens);
    }

    public StatusDoPedido status() {
        return status;
    }

    public UUID reservaId() {
        return reservaId;
    }

    public UUID pagamentoId() {
        return pagamentoId;
    }

    public PedidoCancelado.Motivo motivoDoCancelamento() {
        return motivoDoCancelamento;
    }

    public String detalheDoCancelamento() {
        return detalheDoCancelamento;
    }

    public Instant criadoEm() {
        return criadoEm;
    }

    public Instant atualizadoEm() {
        return atualizadoEm;
    }

    public long versao() {
        return versao;
    }
}
