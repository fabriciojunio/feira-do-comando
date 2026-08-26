package br.com.feira.pedidos.infraestrutura.persistencia;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ChaveDeIdempotenciaRepository extends JpaRepository<ChaveDeIdempotencia, UUID> {

    Optional<ChaveDeIdempotencia> findByChaveAndClienteId(String chave, String clienteId);

    /**
     * Chave velha nao serve mais: nenhum cliente repete uma tentativa dias
     * depois. Guardar para sempre so faz a tabela crescer e o indice unico
     * ficar caro.
     */
    @Modifying
    @Query("delete from ChaveDeIdempotencia c where c.criadaEm < :limite")
    int apagarAntesDe(@Param("limite") Instant limite);
}
