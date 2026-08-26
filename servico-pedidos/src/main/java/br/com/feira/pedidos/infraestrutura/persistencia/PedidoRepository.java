package br.com.feira.pedidos.infraestrutura.persistencia;

import br.com.feira.pedidos.dominio.Pedido;
import br.com.feira.pedidos.aplicacao.portas.Portas;
import br.com.feira.pedidos.dominio.StatusDoPedido;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PedidoRepository extends JpaRepository<Pedido, UUID>, Portas.DePedidos {

    /**
     * O grafo evita o N+1 na listagem: sem ele, cada pedido dispara uma
     * consulta a mais para os itens, e uma pagina de vinte pedidos vira vinte
     * e uma consultas.
     */
    @Override
    @EntityGraph(attributePaths = "itens")
    @Query("select p from Pedido p where p.clienteId = :clienteId order by p.criadoEm desc")
    Page<Pedido> doCliente(@Param("clienteId") String clienteId, Pageable pagina);

    @EntityGraph(attributePaths = "itens")
    @Override
    Optional<Pedido> findById(UUID id);

    @Override
    default Optional<Pedido> porId(UUID id) {
        return findById(id);
    }

    @Override
    default Pedido salvar(Pedido pedido) {
        return save(pedido);
    }

    @Override
    default long quantidadeEm(StatusDoPedido status) {
        return countByStatus(status);
    }

    /**
     * Pedidos que passaram do prazo e continuam em aberto.
     *
     * A varredura consulta por status e data, e o indice composto no banco
     * cobre exatamente esta consulta. Sem indice, isto vira varredura de tabela
     * inteira a cada minuto.
     */
    @Override
    @Query("""
            select p from Pedido p
            where p.status in :abertos and p.atualizadoEm < :limite
            order by p.atualizadoEm asc
            """)
    List<Pedido> emAbertoDesde(@Param("abertos") List<StatusDoPedido> abertos,
                               @Param("limite") Instant limite);

    long countByStatus(StatusDoPedido status);
}
