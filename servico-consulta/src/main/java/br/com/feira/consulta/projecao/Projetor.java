package br.com.feira.consulta.projecao;

import br.com.feira.consulta.dominio.PedidoLido;
import br.com.feira.contratos.EstoqueRecusado;
import br.com.feira.contratos.EstoqueReservado;
import br.com.feira.contratos.Evento;
import br.com.feira.contratos.PagamentoAprovado;
import br.com.feira.contratos.PagamentoEstornado;
import br.com.feira.contratos.PagamentoRecusado;
import br.com.feira.contratos.PedidoCancelado;
import br.com.feira.contratos.PedidoConfirmado;
import br.com.feira.contratos.PedidoCriado;
import br.com.feira.contratos.ReservaLiberada;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Transforma evento em documento de leitura.
 *
 * <h2>Por que o switch nao tem default</h2>
 * {@link Evento} e selado. Sem {@code default}, acrescentar um evento novo aos
 * contratos quebra a compilacao aqui, e alguem precisa decidir o que a tela
 * mostra para ele. Em projecao, evento novo ignorado em silencio e a falha
 * classica: o pedido some da linha do tempo e ninguem descobre ate um cliente
 * reclamar.
 *
 * <h2>Ordem dos eventos</h2>
 * A chave de particao e o id do pedido, entao os eventos de um mesmo pedido
 * chegam em ordem. O que nao esta garantido e a entrega unica, e por isso todo
 * caminho passa por {@link PedidoLido#registrar}, que ignora id repetido.
 *
 * <p>Falta de ordem entre pedidos diferentes nao importa: cada documento e
 * independente.
 */
@Component
public class Projetor {

    private static final Logger log = LoggerFactory.getLogger(Projetor.class);

    private final PedidoLidoRepository repositorio;

    public Projetor(PedidoLidoRepository repositorio) {
        this.repositorio = repositorio;
    }

    /**
     * Aplica o evento e devolve o documento resultante, quando houver.
     *
     * <p>Devolve vazio quando o evento chegou antes do {@link PedidoCriado}
     * daquele pedido. Isso e possivel em reprocessamento a partir do inicio do
     * topico, e a resposta certa e esperar: o evento vai voltar quando o
     * consumidor reprocessar em ordem, e inventar um documento sem os itens
     * produziria uma tela pior do que nenhuma.
     */
    public Optional<PedidoLido> aplicar(Evento evento) {
        if (evento instanceof PedidoCriado criado) {
            return Optional.of(criar(criado));
        }

        var existente = repositorio.findById(evento.correlacao());
        if (existente.isEmpty()) {
            log.warn("Evento {} chegou antes do PedidoCriado do pedido {}. Ignorando.",
                    evento.nome(), evento.correlacao());
            return Optional.empty();
        }

        var pedido = existente.get();
        if (pedido.jaAplicou(evento.id())) {
            return Optional.of(pedido);
        }

        aplicarNoDocumento(evento, pedido);
        return Optional.of(repositorio.save(pedido));
    }

    private PedidoLido criar(PedidoCriado criado) {
        // Reprocessamento traz o PedidoCriado de novo. Recriar zeraria a linha
        // do tempo de um pedido que ja avancou.
        var existente = repositorio.findById(criado.correlacao());
        if (existente.isPresent()) {
            return existente.get();
        }

        var pedido = new PedidoLido(criado.correlacao(), criado.clienteId(), criado.ocorridoEm());
        pedido.setItens(criado.itens().stream()
                .map(i -> new PedidoLido.Item(
                        i.sku(), i.descricao(), i.quantidade(), i.precoUnitario().emReais()))
                .toList());
        pedido.setTotal(criado.total().emReais());
        pedido.setStatus("RECEBIDO");
        pedido.registrar(criado.id(), "Pedido recebido",
                pedido.getItens().size() + " item(ns)", criado.ocorridoEm());

        return repositorio.save(pedido);
    }

    private void aplicarNoDocumento(Evento evento, PedidoLido p) {
        switch (evento) {
            case PedidoCriado ignorado ->
                // Tratado em criar(), antes de chegar aqui.
                    throw new IllegalStateException("PedidoCriado nao passa por este caminho");

            case EstoqueReservado e -> {
                p.setStatus("ESTOQUE_RESERVADO");
                p.registrar(e.id(), "Estoque separado",
                        "Reserva " + e.reservaId(), e.ocorridoEm());
            }

            case EstoqueRecusado e -> {
                p.setStatus("CANCELADO");
                p.setMotivoDoCancelamento("SEM_ESTOQUE");
                p.setDetalheDoCancelamento(e.motivo());
                p.registrar(e.id(), "Sem estoque", e.motivo(), e.ocorridoEm());
            }

            case ReservaLiberada e -> p.registrar(e.id(), "Estoque devolvido",
                    "Reserva " + e.reservaId(), e.ocorridoEm());

            case PagamentoAprovado e -> {
                p.setSituacaoDaCobranca("APROVADA");
                p.setReferenciaExterna(e.referenciaExterna());
                p.registrar(e.id(), "Cobranca aprovada",
                        e.referenciaExterna(), e.ocorridoEm());
            }

            case PagamentoRecusado e -> {
                p.setSituacaoDaCobranca("RECUSADA");
                p.setMotivoDaRecusa(e.motivo().name());
                p.registrar(e.id(), "Cobranca recusada",
                        legivel(e.motivo().name()), e.ocorridoEm());
            }

            // O estorno e o caso que justifica a saga existir: o dinheiro saiu
            // e voltou. A tela precisa dizer isso com todas as letras.
            case PagamentoEstornado e -> {
                p.setSituacaoDaCobranca("ESTORNADA");
                p.registrar(e.id(), "Cobranca estornada",
                        legivel(e.motivo()), e.ocorridoEm());
            }

            case PedidoConfirmado e -> {
                p.setStatus("CONFIRMADO");
                p.registrar(e.id(), "Pedido confirmado", null, e.ocorridoEm());
            }

            case PedidoCancelado e -> {
                p.setStatus("CANCELADO");
                p.setMotivoDoCancelamento(e.motivo().name());
                p.setDetalheDoCancelamento(e.detalhe());
                p.registrar(e.id(), "Pedido cancelado",
                        legivel(e.motivo().name()), e.ocorridoEm());
            }
        }
    }

    private static String legivel(String constante) {
        return constante.replace('_', ' ').toLowerCase();
    }
}
