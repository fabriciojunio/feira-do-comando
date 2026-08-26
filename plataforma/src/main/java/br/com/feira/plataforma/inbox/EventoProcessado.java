package br.com.feira.plataforma.inbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Registro de que um evento ja foi processado.
 *
 * O outro lado do outbox. Como a entrega e ao menos uma vez, o consumidor
 * precisa lembrar o que ja viu. A chave primaria e o id do evento, entao a
 * segunda tentativa de gravar viola a chave e o processamento e abortado sem
 * efeito colateral.
 *
 * Guardar o resultado nao e necessario aqui porque o processamento e
 * idempotente por natureza: a saga ignora evento que nao cabe no estado atual.
 * Em um consumidor que devolve resposta, este registro guardaria tambem o
 * corpo da resposta, para reentregar a mesma coisa em vez de reprocessar.
 */
@Entity
@Table(name = "eventos_processados")
public class EventoProcessado {

    @Id
    @Column(name = "evento_id", nullable = false)
    private UUID eventoId;

    @Column(name = "tipo", nullable = false, length = 60)
    private String tipo;

    @Column(name = "correlacao", nullable = false)
    private UUID correlacao;

    @Column(name = "processado_em", nullable = false)
    private Instant processadoEm;

    @Column(name = "resultado", length = 300)
    private String resultado;

    protected EventoProcessado() {
        // exigido pelo JPA
    }

    public EventoProcessado(UUID eventoId, String tipo, UUID correlacao, String resultado,
                            Instant agora) {
        this.eventoId = eventoId;
        this.tipo = tipo;
        this.correlacao = correlacao;
        this.resultado = resultado == null
                ? null
                : resultado.substring(0, Math.min(resultado.length(), 300));
        this.processadoEm = agora;
    }

    public UUID eventoId() {
        return eventoId;
    }

    public String tipo() {
        return tipo;
    }

    public UUID correlacao() {
        return correlacao;
    }

    public Instant processadoEm() {
        return processadoEm;
    }

    public String resultado() {
        return resultado;
    }
}
