package br.com.feira.consulta.projecao;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.UUID;

public interface EventosPendentes extends MongoRepository<EventoPendente, UUID> {

    List<EventoPendente> findByPedidoIdOrderByOcorridoEmAsc(UUID pedidoId);
}
