package br.com.feira.consulta.projecao;

import br.com.feira.contratos.PedidoCriado;
import br.com.feira.contratos.Topicos;
import br.com.feira.plataforma.mensageria.MapeadorDeEventos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Escuta os tres topicos e alimenta a projecao.
 *
 * <p>Este consumidor le os mesmos eventos que os servicos de negocio leem, mas
 * com grupo proprio. Isso e o que permite reconstruir a projecao inteira do
 * zero: basta apagar a colecao e reposicionar o grupo no inicio do topico, sem
 * tocar em nenhum dos outros servicos.
 *
 * Eventos que chegam antes de PedidoCriado ficam numa colecao duravel e sao
 * reaplicados depois. O proprio documento ignora IDs de eventos repetidos.
 */
@Component
public class ConsumidorDeEventos {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorDeEventos.class);

    private final MapeadorDeEventos mapeador;
    private final Projetor projetor;
    private final EventosPendentes pendentes;
    private final MongoTemplate mongo;

    public ConsumidorDeEventos(MapeadorDeEventos mapeador, Projetor projetor,
                               EventosPendentes pendentes, MongoTemplate mongo) {
        this.mapeador = mapeador;
        this.projetor = projetor;
        this.pendentes = pendentes;
        this.mongo = mongo;
    }

    @KafkaListener(
            topics = {Topicos.PEDIDOS, Topicos.ESTOQUE, Topicos.PAGAMENTOS},
            groupId = "consulta")
    public void receber(String carga) throws Exception {
        var evento = mapeador.deJson(carga);
        var aplicado = projetor.aplicar(evento);
        if (aplicado.isEmpty()) {
            // A gravacao precede o retorno do listener e o commit do offset.
            pendentes.save(new EventoPendente(evento.id(), evento.correlacao(),
                    evento.ocorridoEm(), carga));
            // Fecha a corrida com PedidoCriado gravado por outra instancia.
            reprocessarPedido(evento.correlacao());
            return;
        }
        log.debug("Projecao do pedido {} na versao {}", aplicado.get().getId(),
                aplicado.get().getVersao());
        if (evento instanceof PedidoCriado) {
            reprocessarPedido(evento.correlacao());
        }
    }

    private void reprocessarPedido(UUID pedidoId) throws Exception {
        for (var pendente : pendentes.findByPedidoIdOrderByOcorridoEmAsc(pedidoId)) {
            if (projetor.aplicar(mapeador.deJson(pendente.carga())).isPresent()) {
                // Se cair antes da remocao, o ID no documento torna o replay
                // seguro. Se falhar ao aplicar, o pendente continua salvo.
                pendentes.deleteById(pendente.id());
            }
        }
    }

    /** Recupera tambem a corrida entre a gravacao e a releitura do pedido. */
    @Scheduled(fixedDelayString = "${feira.consulta.reprocessamento-ms:30000}")
    public void reprocessarPendentes() {
        try (var cursor = mongo.stream(new Query(), EventoPendente.class)) {
            cursor.forEach(pendente -> {
                try {
                    if (projetor.aplicar(mapeador.deJson(pendente.carga())).isPresent()) {
                        pendentes.deleteById(pendente.id());
                    }
                } catch (Exception erro) {
                    log.warn("Falha ao reprocessar evento pendente {}", pendente.id(), erro);
                }
            });
        }
    }
}
