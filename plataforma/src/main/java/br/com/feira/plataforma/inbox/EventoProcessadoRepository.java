package br.com.feira.plataforma.inbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * Interface de topo, e nao aninhada dentro do {@code Inbox}.
 *
 * O Spring Data varre interfaces de topo; interface declarada dentro de uma
 * classe anotada com {@code @Component} nao e enxergada, e o contexto sobe
 * reclamando de bean ausente. Foi assim que este arquivo nasceu.
 */
public interface EventoProcessadoRepository extends JpaRepository<EventoProcessado, UUID> {

    @Modifying
    @Query("delete from EventoProcessado e where e.processadoEm < :limite")
    int apagarAntesDe(@Param("limite") Instant limite);
}
