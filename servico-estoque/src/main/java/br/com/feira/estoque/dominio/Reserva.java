package br.com.feira.estoque.dominio;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * O que foi separado para um pedido.
 *
 * Existe como entidade propria, e nao como um campo no item, por dois motivos
 * praticos. Primeiro, a compensacao precisa saber exatamente quanto devolver
 * de cada SKU, e recalcular a partir do pedido no momento do cancelamento
 * daria numero errado se o pedido tivesse mudado. Segundo, o
 * {@code pedidoId} e a chave primaria: reprocessar o mesmo
 * {@code PedidoCriado} esbarra na chave e nao reserva duas vezes.
 */
@Entity
@Table(name = "reservas")
public class Reserva {

    @Id
    @Column(name = "pedido_id", nullable = false)
    private UUID pedidoId;

    @Column(name = "id", nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "situacao", nullable = false, length = 20)
    private Situacao situacao;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "reserva_itens", joinColumns = @JoinColumn(name = "pedido_id"))
    private List<LinhaReservada> linhas = new ArrayList<>();

    @Column(name = "criada_em", nullable = false)
    private Instant criadaEm;

    @Column(name = "encerrada_em")
    private Instant encerradaEm;

    public enum Situacao {
        /** Unidades separadas, aguardando o desfecho do pedido. */
        ATIVA,
        /** Devolvidas ao saldo disponivel. */
        LIBERADA,
        /** Baixadas: o pedido foi confirmado. */
        BAIXADA
    }

    @Embeddable
    public static class LinhaReservada {

        @Column(name = "sku", nullable = false, length = 60)
        private String sku;

        @Column(name = "quantidade", nullable = false)
        private int quantidade;

        protected LinhaReservada() {
            // exigido pelo JPA
        }

        public LinhaReservada(String sku, int quantidade) {
            this.sku = sku;
            this.quantidade = quantidade;
        }

        public String sku() {
            return sku;
        }

        public int quantidade() {
            return quantidade;
        }
    }

    protected Reserva() {
        // exigido pelo JPA
    }

    public static Reserva nova(UUID pedidoId, Map<String, Integer> quantidadePorSku, Instant agora) {
        var reserva = new Reserva();
        reserva.pedidoId = pedidoId;
        reserva.id = UUID.randomUUID();
        reserva.situacao = Situacao.ATIVA;
        reserva.criadaEm = agora;
        quantidadePorSku.forEach((sku, quantidade) ->
                reserva.linhas.add(new LinhaReservada(sku, quantidade)));
        return reserva;
    }

    public boolean ativa() {
        return situacao == Situacao.ATIVA;
    }

    public void marcarLiberada(Instant agora) {
        this.situacao = Situacao.LIBERADA;
        this.encerradaEm = agora;
    }

    public void marcarBaixada(Instant agora) {
        this.situacao = Situacao.BAIXADA;
        this.encerradaEm = agora;
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

    public List<LinhaReservada> linhas() {
        return List.copyOf(linhas);
    }

    public Instant criadaEm() {
        return criadaEm;
    }

    public Instant encerradaEm() {
        return encerradaEm;
    }
}
