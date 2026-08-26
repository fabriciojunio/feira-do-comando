package br.com.feira.estoque.aplicacao;

import br.com.feira.contratos.Topicos;
import br.com.feira.contratos.ReservaLiberada;
import br.com.feira.estoque.dominio.Reserva;
import br.com.feira.estoque.infraestrutura.persistencia.ItemDeEstoqueRepository;
import br.com.feira.estoque.infraestrutura.persistencia.ReservaRepository;
import br.com.feira.plataforma.outbox.RegistroDeSaida;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Fecha uma reserva, devolvendo ou baixando as unidades.
 *
 * <h2>Idempotencia como regra, nao como detalhe</h2>
 * Os dois metodos aqui podem ser chamados varias vezes para o mesmo pedido, e
 * precisam ser inofensivos a partir da segunda. Numa saga isso nao e teoria: o
 * pedido publica a liberacao, o consumidor cai antes de confirmar o offset, e
 * o Kafka reentrega. Se a segunda passagem devolvesse as unidades de novo, o
 * saldo cresceria sozinho, e estoque que cresce sozinho vira venda de
 * mercadoria que nao existe.
 *
 * A protecao e a situacao da reserva: so reserva ATIVA produz efeito.
 */
@Service
public class EncerrarReserva {

    private static final Logger log = LoggerFactory.getLogger(EncerrarReserva.class);

    private final ItemDeEstoqueRepository itens;
    private final ReservaRepository reservas;
    private final RegistroDeSaida saida;
    private final MeterRegistry metricas;
    private final Clock relogio;

    public EncerrarReserva(ItemDeEstoqueRepository itens, ReservaRepository reservas,
                           RegistroDeSaida saida, MeterRegistry metricas, Clock relogio) {
        this.itens = itens;
        this.reservas = reservas;
        this.saida = saida;
        this.metricas = metricas;
        this.relogio = relogio;
    }

    /** Devolve as unidades ao saldo disponivel e avisa quem pediu. */
    @Transactional
    public void liberar(UUID pedidoId, String motivo) {
        var reserva = reservas.findById(pedidoId).orElse(null);
        if (reserva == null) {
            // A recusa por falta de estoque nao cria reserva, e o pedido pode
            // mandar liberar mesmo assim. Nao e erro.
            log.debug("Liberacao pedida para {} sem reserva correspondente", pedidoId);
            return;
        }
        if (!reserva.ativa()) {
            log.debug("Reserva de {} ja estava {}", pedidoId, reserva.situacao());
            return;
        }

        var agora = relogio.instant();
        aplicarNasLinhas(reserva, (item, quantidade) -> item.liberar(quantidade, agora));
        reserva.marcarLiberada(agora);

        saida.gravar(Topicos.ESTOQUE, ReservaLiberada.de(pedidoId, reserva.id(), motivo, agora));
        metricas.counter("feira.estoque.liberacoes").increment();
        log.info("Reserva de {} liberada: {}", pedidoId, motivo);
    }

    /** Confirma a saida da mercadoria. Chamado quando o pedido e confirmado. */
    @Transactional
    public void baixar(UUID pedidoId) {
        var reserva = reservas.findById(pedidoId).orElse(null);
        if (reserva == null || !reserva.ativa()) {
            return;
        }

        var agora = relogio.instant();
        aplicarNasLinhas(reserva, (item, quantidade) -> item.baixar(quantidade, agora));
        reserva.marcarBaixada(agora);

        metricas.counter("feira.estoque.baixas").increment();
        log.info("Reserva de {} baixada", pedidoId);
    }

    private void aplicarNasLinhas(Reserva reserva,
                                  java.util.function.BiConsumer<br.com.feira.estoque.dominio.ItemDeEstoque, Integer> acao) {
        for (var linha : reserva.linhas()) {
            itens.findById(linha.sku()).ifPresent(item -> {
                acao.accept(item, linha.quantidade());
                itens.save(item);
            });
        }
    }
}
