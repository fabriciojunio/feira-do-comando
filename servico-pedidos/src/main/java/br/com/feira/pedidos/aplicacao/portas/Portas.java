package br.com.feira.pedidos.aplicacao.portas;

import br.com.feira.pedidos.dominio.Pedido;
import br.com.feira.pedidos.dominio.StatusDoPedido;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * O que a camada de aplicacao precisa do mundo de fora.
 *
 * <h2>Por que estas interfaces existem</h2>
 * Sem elas, {@code aplicacao} importava {@code infraestrutura} e
 * {@code infraestrutura} importava {@code aplicacao}. Isso e um ciclo, e ciclo
 * entre pacotes significa que nao ha mais camada nenhuma: os dois lados viram
 * um bloco so que nao da para testar nem trocar em pedaco.
 *
 * O teste de arquitetura pegou exatamente isso. As portas sao a correcao.
 *
 * <p>A gravacao de evento e a memoria de eventos consumidos nao estao aqui:
 * elas sao identicas nos tres servicos e vivem no modulo {@code plataforma},
 * como {@code RegistroDeSaida} e {@code RegistroDeEntrada}.
 *
 * <h2>Por que nenhuma delas fala de entidade de persistencia</h2>
 * {@link DeChaves} devolve {@code UUID}, e nao a entidade da tabela de chaves.
 * Se devolvesse, a aplicacao voltaria a depender de um detalhe de banco e o
 * ciclo voltaria pela porta dos fundos.
 */
public interface Portas {

    /** Leitura e escrita de pedidos. */
    interface DePedidos {

        Optional<Pedido> porId(UUID id);

        Page<Pedido> doCliente(String clienteId, Pageable pagina);

        List<Pedido> emAbertoDesde(List<StatusDoPedido> abertos, Instant limite);

        Pedido salvar(Pedido pedido);

        long quantidadeEm(StatusDoPedido status);
    }

    /**
     * Chaves de idempotencia da API. Fala em primitivos de proposito: a
     * aplicacao nao precisa saber que existe uma tabela para isso.
     */
    interface DeChaves {

        Optional<UUID> pedidoDaChave(String chave, String clienteId);

        void registrar(String chave, String clienteId, UUID pedidoId, Instant agora);
    }

}
