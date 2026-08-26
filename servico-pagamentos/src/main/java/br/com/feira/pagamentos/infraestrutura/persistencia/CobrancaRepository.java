package br.com.feira.pagamentos.infraestrutura.persistencia;

import br.com.feira.pagamentos.dominio.Cobranca;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CobrancaRepository extends JpaRepository<Cobranca, UUID> {

    long countBySituacao(Cobranca.Situacao situacao);

    /**
     * Cobrancas paradas em pendente ha tempo demais. Sinaliza que o estoque
     * parou de responder: sem este alerta, o pedido fica preso e ninguem sabe
     * de que lado.
     */
    @Query("""
            select c from Cobranca c
            where c.situacao = br.com.feira.pagamentos.dominio.Cobranca$Situacao.PENDENTE
              and c.criadaEm < :limite
            """)
    List<Cobranca> pendentesDesde(@Param("limite") Instant limite);

    @Query("""
            select coalesce(sum(c.valorCentavos), 0) from Cobranca c
            where c.situacao = br.com.feira.pagamentos.dominio.Cobranca$Situacao.APROVADA
            """)
    long totalAprovadoEmCentavos();
}
