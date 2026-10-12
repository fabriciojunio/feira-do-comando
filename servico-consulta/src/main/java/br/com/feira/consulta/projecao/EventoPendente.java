package br.com.feira.consulta.projecao;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/** Evento recebido antes de o documento do pedido existir. */
@Document(collection = "eventos_pendentes")
public record EventoPendente(@Id UUID id, @Indexed UUID pedidoId,
                             Instant ocorridoEm, String carga) {
}
