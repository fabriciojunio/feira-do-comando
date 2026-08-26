package br.com.feira.consulta;

import br.com.feira.consulta.dominio.PedidoLido;
import br.com.feira.consulta.projecao.PedidoLidoRepository;
import br.com.feira.consulta.projecao.Projetor;
import br.com.feira.contratos.Dinheiro;
import br.com.feira.contratos.EstoqueRecusado;
import br.com.feira.contratos.EstoqueReservado;
import br.com.feira.contratos.Evento;
import br.com.feira.contratos.ItemDoPedido;
import br.com.feira.contratos.PagamentoAprovado;
import br.com.feira.contratos.PagamentoEstornado;
import br.com.feira.contratos.PagamentoRecusado;
import br.com.feira.contratos.PedidoCancelado;
import br.com.feira.contratos.PedidoConfirmado;
import br.com.feira.contratos.PedidoCriado;
import br.com.feira.contratos.ReservaLiberada;
import de.flapdoodle.embed.mongo.spring.autoconfigure.EmbeddedMongoAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.ContextConfiguration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A projecao, contra um MongoDB de verdade.
 *
 * <p>O Mongo e iniciado pelo proprio teste, sem Docker, pela mesma razao dos
 * outros modulos: teste que so roda quando alguem lembra de subir a
 * infraestrutura e teste que nao roda. E boa parte do que interessa aqui,
 * como indice e serializacao de documento aninhado, um dublê nao reproduz.
 */
@DataMongoTest
@ImportAutoConfiguration(EmbeddedMongoAutoConfiguration.class)
@ContextConfiguration(classes = ProjecaoDoPedidoTest.Configuracao.class)
@DisplayName("Projecao do pedido")
class ProjecaoDoPedidoTest {

    /**
     * Contexto minimo: so o Mongo e o projetor.
     *
     * <p>Sem isto o teste encontraria a {@code Aplicacao} e subiria junto a
     * configuracao de Kafka, que uma fatia de Mongo nao configura. O que este
     * teste verifica e a projecao contra o banco, e nada mais precisa existir
     * para isso.
     *
     * <p>E uma {@code @Configuration} comum, e nao uma
     * {@code @SpringBootConfiguration}: a segunda colidiria com a
     * {@code Aplicacao}, que esta no mesmo pacote, e o Spring recusa duas.
     */
    @org.springframework.context.annotation.Configuration
    @EnableMongoRepositories(basePackageClasses = PedidoLidoRepository.class)
    @Import(Projetor.class)
    static class Configuracao {
    }

    @Autowired
    private Projetor projetor;

    @Autowired
    private PedidoLidoRepository repositorio;

    private UUID pedidoId;
    private Instant agora;

    @BeforeEach
    void limpar() {
        repositorio.deleteAll();
        pedidoId = UUID.randomUUID();
        agora = Instant.parse("2026-08-26T10:00:00Z");
    }

    // ---- fabricas de evento ----

    private PedidoCriado criado() {
        return new PedidoCriado(
                UUID.randomUUID(), pedidoId, agora, Evento.VERSAO_ATUAL,
                "cliente-1",
                List.of(new ItemDoPedido("CAF-500", "Cafe", 2, new Dinheiro(2490))),
                new Dinheiro(4980));
    }

    private EstoqueReservado reservado() {
        return new EstoqueReservado(UUID.randomUUID(), pedidoId, agora.plusSeconds(1),
                Evento.VERSAO_ATUAL, UUID.randomUUID());
    }

    private PagamentoAprovado aprovado() {
        return new PagamentoAprovado(UUID.randomUUID(), pedidoId, agora.plusSeconds(2),
                Evento.VERSAO_ATUAL, UUID.randomUUID(), "tx-abc123", new Dinheiro(4980));
    }

    private PedidoConfirmado confirmado() {
        return new PedidoConfirmado(UUID.randomUUID(), pedidoId, agora.plusSeconds(3),
                Evento.VERSAO_ATUAL, new Dinheiro(4980));
    }

    private PedidoLido lido() {
        return repositorio.findById(pedidoId).orElseThrow();
    }

    @Nested
    @DisplayName("Montagem do documento")
    class Montagem {

        @Test
        @DisplayName("o pedido criado vira documento com itens e total")
        void criaDocumento() {
            projetor.aplicar(criado());

            var p = lido();
            assertThat(p.getClienteId()).isEqualTo("cliente-1");
            assertThat(p.getStatus()).isEqualTo("RECEBIDO");
            assertThat(p.getItens()).hasSize(1);
            assertThat(p.getItens().get(0).sku()).isEqualTo("CAF-500");
            assertThat(p.getTotal()).isEqualByComparingTo("49.80");
        }

        /**
         * O documento existe para responder numa leitura so. Se os itens
         * ficassem fora dele, a consulta voltaria a precisar de junção, que e
         * exatamente o problema que este servico resolve.
         */
        @Test
        @DisplayName("os itens ficam dentro do documento, nao em outra colecao")
        void itensAninhados() {
            projetor.aplicar(criado());

            assertThat(repositorio.findById(pedidoId).orElseThrow().getItens())
                    .allSatisfy(i -> {
                        assertThat(i.descricao()).isNotBlank();
                        assertThat(i.precoUnitario()).isNotNull();
                    });
        }

        @Test
        @DisplayName("o caminho feliz completo deixa a linha do tempo em ordem")
        void caminhoFeliz() {
            projetor.aplicar(criado());
            projetor.aplicar(reservado());
            projetor.aplicar(aprovado());
            projetor.aplicar(confirmado());

            var p = lido();
            assertThat(p.getStatus()).isEqualTo("CONFIRMADO");
            assertThat(p.getSituacaoDaCobranca()).isEqualTo("APROVADA");
            assertThat(p.getReferenciaExterna()).isEqualTo("tx-abc123");
            assertThat(p.getLinhaDoTempo()).extracting(PedidoLido.Marco::o_que)
                    .containsExactly("Pedido recebido", "Estoque separado",
                            "Cobranca aprovada", "Pedido confirmado");
        }
    }

    @Nested
    @DisplayName("Entrega repetida")
    class Repeticao {

        /**
         * Kafka entrega ao menos uma vez. Sem a guarda, reprocessar uma
         * particao duplicaria a linha do tempo, e o cliente veria "estoque
         * separado" duas vezes num pedido que reservou uma.
         */
        @Test
        @DisplayName("o mesmo evento duas vezes nao duplica a linha do tempo")
        void eventoRepetido() {
            var reserva = reservado();

            projetor.aplicar(criado());
            projetor.aplicar(reserva);
            projetor.aplicar(reserva);

            assertThat(lido().getLinhaDoTempo()).hasSize(2);
        }

        @Test
        @DisplayName("o PedidoCriado repetido nao zera um pedido que ja avancou")
        void criadoRepetido() {
            var criacao = criado();

            projetor.aplicar(criacao);
            projetor.aplicar(reservado());
            projetor.aplicar(criacao);

            var p = lido();
            assertThat(p.getStatus()).isEqualTo("ESTOQUE_RESERVADO");
            assertThat(p.getLinhaDoTempo()).hasSize(2);
        }

        @Test
        @DisplayName("a versao conta os eventos aplicados, e nao as entregas")
        void versaoContaAplicados() {
            var reserva = reservado();

            projetor.aplicar(criado());
            projetor.aplicar(reserva);
            projetor.aplicar(reserva);
            projetor.aplicar(reserva);

            assertThat(lido().getVersao()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Evento fora de ordem")
    class ForaDeOrdem {

        /**
         * Reprocessar do inicio do topico pode trazer um evento antes do
         * PedidoCriado daquele pedido. Inventar um documento sem os itens
         * produziria uma tela pior do que nenhuma.
         */
        @Test
        @DisplayName("evento antes do PedidoCriado nao cria documento pela metade")
        void semDocumentoAinda() {
            var resultado = projetor.aplicar(reservado());

            assertThat(resultado).isEmpty();
            assertThat(repositorio.findById(pedidoId)).isEmpty();
        }

        @Test
        @DisplayName("depois que o PedidoCriado chega, o evento seguinte aplica normalmente")
        void aplicaDepois() {
            projetor.aplicar(reservado());
            projetor.aplicar(criado());
            projetor.aplicar(reservado());

            assertThat(lido().getStatus()).isEqualTo("ESTOQUE_RESERVADO");
        }
    }

    @Nested
    @DisplayName("Caminhos de falha")
    class Falhas {

        @Test
        @DisplayName("sem estoque cancela e guarda o motivo legivel")
        void semEstoque() {
            projetor.aplicar(criado());
            projetor.aplicar(new EstoqueRecusado(
                    UUID.randomUUID(), pedidoId, agora.plusSeconds(1), Evento.VERSAO_ATUAL,
                    List.of("CAF-500"), "Sem estoque: CAF-500"));

            var p = lido();
            assertThat(p.getStatus()).isEqualTo("CANCELADO");
            assertThat(p.getMotivoDoCancelamento()).isEqualTo("SEM_ESTOQUE");
            assertThat(p.getDetalheDoCancelamento()).contains("CAF-500");
        }

        @Test
        @DisplayName("pagamento recusado guarda o motivo em forma de gente")
        void pagamentoRecusado() {
            projetor.aplicar(criado());
            projetor.aplicar(reservado());
            projetor.aplicar(new PagamentoRecusado(
                    UUID.randomUUID(), pedidoId, agora.plusSeconds(2), Evento.VERSAO_ATUAL,
                    UUID.randomUUID(), PagamentoRecusado.Motivo.SALDO_INSUFICIENTE));

            var p = lido();
            assertThat(p.getSituacaoDaCobranca()).isEqualTo("RECUSADA");
            assertThat(p.getMotivoDaRecusa()).isEqualTo("SALDO_INSUFICIENTE");
            assertThat(p.getLinhaDoTempo()).last()
                    .extracting(PedidoLido.Marco::detalhe)
                    .isEqualTo("saldo insuficiente");
        }

        /**
         * O estorno e o caso que justifica a saga existir: o dinheiro saiu e
         * voltou. Se a projecao nao registrar isso, a tela nao tem como
         * explicar ao cliente o que aconteceu com o dinheiro dele.
         */
        @Test
        @DisplayName("o estorno aparece na linha do tempo, com todas as letras")
        void estorno() {
            projetor.aplicar(criado());
            projetor.aplicar(reservado());
            projetor.aplicar(aprovado());
            projetor.aplicar(new PagamentoEstornado(
                    UUID.randomUUID(), pedidoId, agora.plusSeconds(4), Evento.VERSAO_ATUAL,
                    UUID.randomUUID(), new Dinheiro(4980), "PEDIDO_CANCELADO"));
            projetor.aplicar(new PedidoCancelado(
                    UUID.randomUUID(), pedidoId, agora.plusSeconds(5), Evento.VERSAO_ATUAL,
                    PedidoCancelado.Motivo.PEDIDO_DO_CLIENTE, null));

            var p = lido();
            assertThat(p.getSituacaoDaCobranca()).isEqualTo("ESTORNADA");
            assertThat(p.getStatus()).isEqualTo("CANCELADO");
            assertThat(p.getLinhaDoTempo()).extracting(PedidoLido.Marco::o_que)
                    .contains("Cobranca aprovada", "Cobranca estornada", "Pedido cancelado");
        }

        @Test
        @DisplayName("a devolucao do estoque tambem entra na linha do tempo")
        void reservaLiberada() {
            projetor.aplicar(criado());
            projetor.aplicar(reservado());
            projetor.aplicar(new ReservaLiberada(
                    UUID.randomUUID(), pedidoId, agora.plusSeconds(3), Evento.VERSAO_ATUAL,
                    UUID.randomUUID(), "pedido cancelado"));

            assertThat(lido().getLinhaDoTempo()).extracting(PedidoLido.Marco::o_que)
                    .contains("Estoque devolvido");
        }
    }

    @Nested
    @DisplayName("Consulta")
    class Consulta {

        @Test
        @DisplayName("lista por cliente, do mais novo para o mais antigo")
        void listaPorCliente() {
            for (int i = 0; i < 3; i++) {
                pedidoId = UUID.randomUUID();
                agora = agora.plusSeconds(60);
                projetor.aplicar(criado());
            }

            var pagina = repositorio.findByClienteIdOrderByCriadoEmDesc(
                    "cliente-1", org.springframework.data.domain.PageRequest.of(0, 10));

            assertThat(pagina.getTotalElements()).isEqualTo(3);
            assertThat(pagina.getContent()).isSortedAccordingTo(
                    (a, b) -> b.getCriadoEm().compareTo(a.getCriadoEm()));
        }

        /**
         * Sem o filtro por cliente, saber o id de um pedido bastaria para ler
         * o pedido de outra pessoa, e id de pedido aparece em URL.
         */
        @Test
        @DisplayName("um cliente nao ve o pedido de outro")
        void naoVazaEntreClientes() {
            projetor.aplicar(criado());

            var pagina = repositorio.findByClienteIdOrderByCriadoEmDesc(
                    "cliente-2", org.springframework.data.domain.PageRequest.of(0, 10));

            assertThat(pagina.getTotalElements()).isZero();
        }

        @Test
        @DisplayName("filtra por status")
        void filtraPorStatus() {
            projetor.aplicar(criado());
            projetor.aplicar(reservado());
            projetor.aplicar(aprovado());
            projetor.aplicar(confirmado());

            var confirmados = repositorio.findByClienteIdAndStatusOrderByCriadoEmDesc(
                    "cliente-1", "CONFIRMADO", org.springframework.data.domain.PageRequest.of(0, 10));
            var cancelados = repositorio.findByClienteIdAndStatusOrderByCriadoEmDesc(
                    "cliente-1", "CANCELADO", org.springframework.data.domain.PageRequest.of(0, 10));

            assertThat(confirmados.getTotalElements()).isEqualTo(1);
            assertThat(cancelados.getTotalElements()).isZero();
        }
    }
}
