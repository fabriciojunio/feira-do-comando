package br.com.feira.plataforma.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<MensagemDoOutbox, UUID> {

    /**
     * Pega o proximo lote a publicar e trava as linhas.
     *
     * {@code PESSIMISTIC_WRITE} com {@code SKIP LOCKED} e o que permite rodar
     * mais de uma instancia do servico sem publicar a mesma mensagem duas
     * vezes: a segunda instancia simplesmente pula o que a primeira ja pegou,
     * em vez de ficar esperando o lock.
     *
     * Sem o {@code SKIP LOCKED}, duas instancias viram uma fila serializada e o
     * throughput cai ao de uma so.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select m from MensagemDoOutbox m
            where m.publicadaEm is null
              and m.tentativas < :tentativasMaximas
              and m.tentarApos <= :agora
            order by m.criadaEm asc
            """)
    List<MensagemDoOutbox> proximasParaPublicar(@Param("agora") Instant agora,
                                                @Param("tentativasMaximas") int tentativasMaximas,
                                                Limit limite);

    /** Mensagens que esgotaram as tentativas e precisam de olho humano. */
    @Query("""
            select m from MensagemDoOutbox m
            where m.publicadaEm is null and m.tentativas >= :tentativasMaximas
            order by m.criadaEm asc
            """)
    List<MensagemDoOutbox> travadas(@Param("tentativasMaximas") int tentativasMaximas);

    long countByPublicadaEmIsNull();

    /**
     * Limpeza do que ja foi publicado. A tabela e a mais quente do servico e
     * cresce na velocidade dos pedidos; sem expurgo, o indice degrada.
     */
    @Modifying
    @Query("delete from MensagemDoOutbox m where m.publicadaEm is not null and m.publicadaEm < :limite")
    int apagarPublicadasAntesDe(@Param("limite") Instant limite);
}
