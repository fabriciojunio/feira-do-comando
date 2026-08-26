package br.com.feira.estoque.infraestrutura.persistencia;

import br.com.feira.estoque.dominio.Reserva;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservaRepository extends JpaRepository<Reserva, UUID> {

    /** O grafo evita uma consulta por reserva para carregar as linhas. */
    @EntityGraph(attributePaths = "linhas")
    @Override
    Optional<Reserva> findById(UUID pedidoId);

    /**
     * Reservas ativas ha tempo demais. Se o servico de pedidos sumir, alguem
     * precisa perceber que ha mercadoria presa; esta consulta alimenta o
     * alerta.
     */
    @EntityGraph(attributePaths = "linhas")
    List<Reserva> findBySituacaoAndCriadaEmBefore(Reserva.Situacao situacao, Instant limite);

    long countBySituacao(Reserva.Situacao situacao);
}
