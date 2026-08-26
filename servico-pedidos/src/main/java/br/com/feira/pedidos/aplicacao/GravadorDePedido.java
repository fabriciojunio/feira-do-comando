package br.com.feira.pedidos.aplicacao;

import br.com.feira.contratos.Topicos;
import br.com.feira.pedidos.aplicacao.portas.Portas;
import br.com.feira.pedidos.dominio.Pedido;
import br.com.feira.plataforma.outbox.RegistroDeSaida;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * A parte transacional da criacao do pedido.
 *
 * <p>Existe separada de {@link CriarPedido} por um motivo especifico. Quando a
 * restricao de unicidade da chave de idempotencia estoura, a transacao fica
 * marcada para desfazer e o contexto de persistencia fica inutilizavel: nao da
 * para consultar o pedido vencedor e devolver a resposta boa de dentro dela,
 * porque o commit vai falhar de qualquer jeito. A recuperacao precisa acontecer
 * do lado de fora, e para isso o limite transacional tem que terminar aqui.
 *
 * <p>Sem essa separacao o comportamento fica sutilmente errado: o banco garante
 * o pedido unico, que e o que importa, mas as requisicoes perdedoras da corrida
 * recebem 500 em vez do pedido que ja existe.
 */
@Component
class GravadorDePedido {

    private final Portas.DePedidos pedidos;
    private final Portas.DeChaves chaves;
    private final RegistroDeSaida saida;
    private final Clock relogio;

    GravadorDePedido(Portas.DePedidos pedidos, Portas.DeChaves chaves,
                     RegistroDeSaida saida, Clock relogio) {
        this.pedidos = pedidos;
        this.chaves = chaves;
        this.saida = saida;
        this.relogio = relogio;
    }

    /**
     * Grava pedido, chave e mensagem de saida numa transacao so. Ou os tres
     * existem, ou nenhum.
     */
    @Transactional
    Pedido gravar(CriarPedido.Entrada entrada) {
        var agora = relogio.instant();
        var pedido = Pedido.abrir(UUID.randomUUID(), entrada.clienteId(), entrada.itens(), agora);

        pedidos.salvar(pedido);
        if (temChave(entrada)) {
            chaves.registrar(entrada.chaveDeIdempotencia(), entrada.clienteId(), pedido.id(), agora);
        }
        saida.gravar(Topicos.PEDIDOS, pedido.eventoDeAbertura(agora));
        return pedido;
    }

    /**
     * Le em transacao propria: quem chama pode estar se recuperando de uma
     * transacao que acabou de ser desfeita.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    Pedido porChave(CriarPedido.Entrada entrada) {
        if (!temChave(entrada)) {
            return null;
        }
        return chaves.pedidoDaChave(entrada.chaveDeIdempotencia(), entrada.clienteId())
                .flatMap(pedidos::porId)
                .orElse(null);
    }

    static boolean temChave(CriarPedido.Entrada entrada) {
        return entrada.chaveDeIdempotencia() != null && !entrada.chaveDeIdempotencia().isBlank();
    }
}
