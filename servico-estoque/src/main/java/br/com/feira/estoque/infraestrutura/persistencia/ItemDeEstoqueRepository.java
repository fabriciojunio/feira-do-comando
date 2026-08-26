package br.com.feira.estoque.infraestrutura.persistencia;

import br.com.feira.estoque.dominio.ItemDeEstoque;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ItemDeEstoqueRepository extends JpaRepository<ItemDeEstoque, String> {

    /**
     * Itens perto de acabar. Consulta do painel de reposicao: ninguem quer
     * descobrir que o SKU zerou pelo evento de pedido recusado.
     */
    @Query("select i from ItemDeEstoque i where i.disponivel <= :limite order by i.disponivel asc")
    List<ItemDeEstoque> abaixoDe(@Param("limite") int limite);

    @Query("select coalesce(sum(i.reservado), 0) from ItemDeEstoque i")
    long totalReservado();
}
