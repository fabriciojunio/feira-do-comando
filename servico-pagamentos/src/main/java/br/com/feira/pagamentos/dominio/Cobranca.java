package br.com.feira.pagamentos.dominio;

import br.com.feira.contratos.Dinheiro;
import br.com.feira.contratos.PagamentoRecusado;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Uma tentativa de cobrar um pedido.
 *
 * <h2>Por que a chave primaria e o pedido</h2>
 * Cobrar duas vezes o mesmo pedido e o pior defeito que este servico pode ter,
 * e num sistema de mensageria isso deixa de ser hipotese: basta o Kafka
 * reentregar. Usar o id do pedido como chave primaria faz o banco recusar a
 * segunda cobranca, e a garantia deixa de depender de alguem lembrar de
 * checar.
 *
 * <h2>Estados</h2>
 * A cobranca so anda para frente. Aprovada vira estornada; recusada nao vira
 * nada. Nao existe caminho que leve uma cobranca estornada de volta a
 * aprovada: o dinheiro ja voltou, e uma nova cobranca e uma cobranca nova.
 */
@Entity
@Table(name = "cobrancas")
public class Cobranca {

    @Id
    @Column(name = "pedido_id", nullable = false)
    private UUID pedidoId;

    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "valor_centavos", nullable = false)
    private long valorCentavos;

    @Enumerated(EnumType.STRING)
    @Column(name = "situacao", nullable = false, length = 20)
    private Situacao situacao;

    @Column(name = "referencia_externa", length = 80)
    private String referenciaExterna;

    @Enumerated(EnumType.STRING)
    @Column(name = "motivo_recusa", length = 30)
    private PagamentoRecusado.Motivo motivoDaRecusa;

    @Column(name = "criada_em", nullable = false)
    private Instant criadaEm;

    @Column(name = "encerrada_em")
    private Instant encerradaEm;

    @Version
    @Column(name = "versao", nullable = false)
    private long versao;

    public enum Situacao {
        /**
         * Registrada quando o pedido nasce, antes de existir autorizacao.
         *
         * Este servico guarda o valor no momento do PedidoCriado porque o
         * evento que dispara a cobranca, o EstoqueReservado, nao carrega
         * valor. Cada servico mantem o pedaco de estado de que precisa, em vez
         * de perguntar ao vizinho na hora de agir; perguntar transformaria a
         * cobranca numa chamada sincrona e devolveria o acoplamento que a
         * mensageria existe para remover.
         */
        PENDENTE,
        APROVADA,
        RECUSADA,
        ESTORNADA
    }

    protected Cobranca() {
        // exigido pelo JPA
    }

    private Cobranca(UUID pedidoId, Dinheiro valor, Instant agora) {
        this.pedidoId = pedidoId;
        this.id = UUID.randomUUID();
        this.valorCentavos = valor.centavos();
        this.criadaEm = agora;
    }

    /** Registrada com o valor do pedido, aguardando o momento de cobrar. */
    public static Cobranca pendente(UUID pedidoId, Dinheiro valor, Instant agora) {
        var cobranca = new Cobranca(pedidoId, valor, agora);
        cobranca.situacao = Situacao.PENDENTE;
        return cobranca;
    }

    /**
     * @return verdadeiro se esta chamada aprovou agora. Falso quando a cobranca
     *         nao estava mais pendente, o que cobre a reentrega da mensagem.
     */
    public boolean aprovar(String referencia, Instant agora) {
        if (situacao != Situacao.PENDENTE) {
            return false;
        }
        this.situacao = Situacao.APROVADA;
        this.referenciaExterna = referencia;
        return true;
    }

    public boolean recusar(PagamentoRecusado.Motivo motivo, String referencia, Instant agora) {
        if (situacao != Situacao.PENDENTE) {
            return false;
        }
        this.situacao = Situacao.RECUSADA;
        this.motivoDaRecusa = motivo;
        this.referenciaExterna = referencia;
        this.encerradaEm = agora;
        return true;
    }

    /**
     * @return verdadeiro se o estorno aconteceu agora. Falso quando a cobranca
     *         nao esta em estado de estornar, o que inclui o caso comum de
     *         reentrega da mensagem de estorno.
     */
    public boolean estornar(Instant agora) {
        if (situacao != Situacao.APROVADA) {
            return false;
        }
        this.situacao = Situacao.ESTORNADA;
        this.encerradaEm = agora;
        return true;
    }

    public boolean aprovada() {
        return situacao == Situacao.APROVADA;
    }

    public boolean pendente() {
        return situacao == Situacao.PENDENTE;
    }

    public Dinheiro valor() {
        return new Dinheiro(valorCentavos);
    }

    public UUID pedidoId() {
        return pedidoId;
    }

    public UUID id() {
        return id;
    }

    public Situacao situacao() {
        return situacao;
    }

    public String referenciaExterna() {
        return referenciaExterna;
    }

    public PagamentoRecusado.Motivo motivoDaRecusa() {
        return motivoDaRecusa;
    }

    public Instant criadaEm() {
        return criadaEm;
    }

    public Instant encerradaEm() {
        return encerradaEm;
    }

    public long versao() {
        return versao;
    }
}
