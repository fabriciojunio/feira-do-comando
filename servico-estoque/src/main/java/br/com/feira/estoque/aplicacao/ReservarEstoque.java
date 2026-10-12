package br.com.feira.estoque.aplicacao;

import br.com.feira.contratos.EstoqueRecusado;
import br.com.feira.contratos.EstoqueReservado;
import br.com.feira.contratos.ItemDoPedido;
import br.com.feira.contratos.PedidoCriado;
import br.com.feira.contratos.Topicos;
import br.com.feira.estoque.dominio.ItemDeEstoque;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tenta separar as unidades de um pedido.
 *
 * <h2>Tudo ou nada</h2>
 * Se um SKU faltar, nada e reservado. Reservar parcialmente deixaria o cliente
 * com meio pedido e o galpao com mercadoria presa por uma compra que nunca vai
 * fechar. A saga do lado do pedido tambem nao saberia o que fazer com uma
 * resposta pela metade.
 *
 * <h2>Por que os SKUs sao percorridos em ordem</h2>
 * Dois pedidos com os mesmos itens em ordens diferentes travariam um ao outro
 * se cada um pegasse as linhas em ordem propria. Ordenar por SKU antes de
 * tocar no banco faz todo mundo pedir as linhas na mesma sequencia, que e a
 * receita padrao para nao criar impasse.
 */
@Service
public class ReservarEstoque {

    private static final Logger log = LoggerFactory.getLogger(ReservarEstoque.class);

    private final ItemDeEstoqueRepository itens;
    private final ReservaRepository reservas;
    private final RegistroDeSaida saida;
    private final MeterRegistry metricas;
    private final Clock relogio;

    public ReservarEstoque(ItemDeEstoqueRepository itens, ReservaRepository reservas,
                           RegistroDeSaida saida, MeterRegistry metricas, Clock relogio) {
        this.itens = itens;
        this.reservas = reservas;
        this.saida = saida;
        this.metricas = metricas;
        this.relogio = relogio;
    }

    @Transactional
    public void executar(PedidoCriado pedido) {
        var jaReservado = reservas.findById(pedido.correlacao());
        if (jaReservado.isPresent()) {
            // O inbox ja barra o evento repetido; esta checagem cobre o caso de
            // o mesmo pedido chegar por um evento com id diferente, o que
            // acontece quando alguem reprocessa uma fila antiga na mao.
            log.info("Pedido {} ja tinha reserva. Nada a fazer.", pedido.correlacao());
            return;
        }

        var quantidadePorSku = agrupar(pedido.itens());
        var faltantes = new ArrayList<String>();
        var reservados = new LinkedHashMap<ItemDeEstoque, Integer>();
        var agora = relogio.instant();

        for (var linha : quantidadePorSku.entrySet()) {
            var item = itens.findById(linha.getKey()).orElse(null);
            if (item == null || !item.reservar(linha.getValue(), agora)) {
                faltantes.add(linha.getKey());
                continue;
            }
            reservados.put(item, linha.getValue());
        }

        if (!faltantes.isEmpty()) {
            // Devolve o que ja tinha sido separado nesta mesma tentativa. Sem
            // isto, um pedido recusado deixaria unidades presas para sempre.
            reservados.forEach((item, quantidade) -> item.liberar(quantidade, agora));

            saida.gravar(Topicos.ESTOQUE,
                    EstoqueRecusado.de(pedido.correlacao(), faltantes,
                            "sem saldo disponivel", agora));
            metricas.counter("feira.estoque.recusas").increment();
            log.info("Pedido {} recusado por falta de {}", pedido.correlacao(), faltantes);
            return;
        }

        itens.saveAll(reservados.keySet());
        var reserva = reservas.save(Reserva.nova(pedido.correlacao(), quantidadePorSku, agora));

        saida.gravar(Topicos.ESTOQUE,
                EstoqueReservado.de(pedido.correlacao(), reserva.id(), agora));
        metricas.counter("feira.estoque.reservas").increment();
        log.info("Pedido {} reservado com {} SKUs", pedido.correlacao(), quantidadePorSku.size());
    }

    /**
     * Junta linhas do mesmo SKU e ordena. A juncao importa porque o cliente
     * pode adicionar o mesmo produto duas vezes no carrinho, e reservar duas
     * vezes em separado daria dois pedidos de uma unidade em vez de um pedido
     * de duas.
     */
    private static Map<String, Integer> agrupar(List<ItemDoPedido> itens) {
        var agrupado = new java.util.TreeMap<String, Integer>();
        for (ItemDoPedido item : itens) {
            agrupado.merge(item.sku(), item.quantidade(), Math::addExact);
        }
        return agrupado;
    }
}
