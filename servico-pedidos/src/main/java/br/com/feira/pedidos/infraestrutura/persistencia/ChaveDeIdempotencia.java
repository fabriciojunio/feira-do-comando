package br.com.feira.pedidos.infraestrutura.persistencia;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

/**
 * Liga uma chave de idempotencia ao pedido que ela criou.
 *
 * A unicidade e por (chave, cliente), e nao so por chave. Dois clientes
 * diferentes podem, por acaso, escolher o mesmo texto; um deles nao pode
 * receber o pedido do outro. Chave de idempotencia sem escopo de dono e uma
 * forma discreta de vazar dado entre contas.
 */
@Entity
@Table(name = "chaves_idempotencia",
        uniqueConstraints = @UniqueConstraint(name = "chave_por_cliente",
                columnNames = {"chave", "cliente_id"}))
public class ChaveDeIdempotencia {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "chave", nullable = false, length = 120)
    private String chave;

    @Column(name = "cliente_id", nullable = false, length = 80)
    private String clienteId;

    @Column(name = "pedido_id", nullable = false)
    private UUID pedidoId;

    @Column(name = "criada_em", nullable = false)
    private Instant criadaEm;

    protected ChaveDeIdempotencia() {
        // exigido pelo JPA
    }

    public ChaveDeIdempotencia(String chave, String clienteId, UUID pedidoId, Instant agora) {
        this.id = UUID.randomUUID();
        this.chave = chave;
        this.clienteId = clienteId;
        this.pedidoId = pedidoId;
        this.criadaEm = agora;
    }

    public String chave() {
        return chave;
    }

    public String clienteId() {
        return clienteId;
    }

    public UUID pedidoId() {
        return pedidoId;
    }

    public Instant criadaEm() {
        return criadaEm;
    }
}
