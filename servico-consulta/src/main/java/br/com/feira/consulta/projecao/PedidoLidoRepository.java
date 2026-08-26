package br.com.feira.consulta.projecao;

import br.com.feira.consulta.dominio.PedidoLido;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.UUID;

/**
 * Acesso ao modelo de leitura.
 *
 * <p>As duas consultas abaixo sao a razao de o servico existir: hoje elas
 * exigiriam tres chamadas de rede e uma juncao feita no navegador. Ambas usam
 * indice declarado no proprio documento.
 */
public interface PedidoLidoRepository extends MongoRepository<PedidoLido, UUID> {

    Page<PedidoLido> findByClienteIdOrderByCriadoEmDesc(String clienteId, Pageable pagina);

    Page<PedidoLido> findByClienteIdAndStatusOrderByCriadoEmDesc(
            String clienteId, String status, Pageable pagina);
}
