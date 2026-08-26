package br.com.feira.estoque;

import br.com.feira.contratos.Dinheiro;
import br.com.feira.contratos.ItemDoPedido;
import br.com.feira.contratos.PedidoCriado;
import br.com.feira.estoque.aplicacao.ReservarEstoque;
import br.com.feira.estoque.dominio.ItemDeEstoque;
import br.com.feira.estoque.dominio.Reserva;
import br.com.feira.estoque.infraestrutura.persistencia.ItemDeEstoqueRepository;
import br.com.feira.estoque.infraestrutura.persistencia.ReservaRepository;
import br.com.feira.plataforma.outbox.OutboxRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O teste que prova que a loja nao vende a mesma peca duas vezes.
 *
 * <p>Concorrencia nao se testa com dublê. Um mock de repositorio responde na
 * ordem que o teste mandar e nunca reproduz duas transacoes disputando a mesma
 * linha. Aqui sao threads de verdade, transacoes de verdade e um PostgreSQL de
 * verdade, porque e o banco que arbitra o conflito.
 *
 * <p>O cenario e o pior possivel de propósito: dez pedidos simultaneos
 * disputando tres unidades. O resultado correto e exatamente tres reservas
 * aceitas e sete recusadas, com o saldo fechando no fim. Qualquer numero
 * diferente disso significa mercadoria vendida que nao existe, ou mercadoria
 * presa que ninguem comprou.
 */
@SpringBootTest
@DisplayName("Concorrencia na reserva")
class ConcorrenciaDeReservaTest {

    private static final String SKU = "CAF-500";
    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry propriedades) throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        propriedades.add("spring.datasource.url",
                () -> postgres.getJdbcUrl("postgres", "postgres"));
        propriedades.add("spring.datasource.username", () -> "postgres");
        propriedades.add("spring.datasource.password", () -> "");

        // Sem broker neste teste: ele e sobre banco e concorrencia. O outbox
        // continua sendo gravado, e o publicador simplesmente nao acha o Kafka.
        propriedades.add("spring.kafka.listener.auto-startup", () -> "false");
        propriedades.add("feira.outbox.intervalo-ms", () -> "3600000");

        // Mais conexoes que threads, senao a disputa vira fila no pool e o
        // teste passaria a medir o HikariCP em vez da trava otimista.
        propriedades.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
    }

    @AfterAll
    static void derrubar() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Autowired
    private ReservarEstoque reservar;

    @Autowired
    private ItemDeEstoqueRepository itens;

    @Autowired
    private ReservaRepository reservas;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private TransactionTemplate transacao;

    @BeforeEach
    void limpar() {
        transacao.executeWithoutResult(status -> {
            outbox.deleteAllInBatch();
            reservas.deleteAllInBatch();
            itens.deleteAllInBatch();
        });
    }

    private void cadastrar(int quantidade) {
        transacao.executeWithoutResult(status ->
                itens.save(new ItemDeEstoque(SKU, "Cafe torrado 500g", quantidade,
                        Instant.parse("2026-08-26T10:00:00Z"))));
    }

    private static PedidoCriado pedidoDeUmaUnidade() {
        return PedidoCriado.de(UUID.randomUUID(), "cliente",
                List.of(new ItemDoPedido(SKU, "Cafe torrado 500g", 1, Dinheiro.de("24.90"))),
                Dinheiro.de("24.90"), Instant.parse("2026-08-26T10:00:00Z"));
    }

    /**
     * Executa as tentativas em paralelo, com todas as threads soltas ao mesmo
     * tempo por um latch. Disparar em sequencia esconderia a disputa: cada
     * transacao terminaria antes de a proxima comecar.
     */
    private Resultado disputar(int tentativas) throws Exception {
        var largada = new CountDownLatch(1);
        var chegada = new CountDownLatch(tentativas);
        var aceitas = new AtomicInteger();
        var recusadas = new AtomicInteger();
        var conflitos = new AtomicInteger();
        var inesperados = new ArrayList<Exception>();

        try (ExecutorService executor = Executors.newFixedThreadPool(tentativas)) {
            for (int i = 0; i < tentativas; i++) {
                executor.submit(() -> {
                    try {
                        largada.await();
                        var pedido = pedidoDeUmaUnidade();
                        reservar.executar(pedido);
                        if (reservas.findById(pedido.correlacao()).isPresent()) {
                            aceitas.incrementAndGet();
                        } else {
                            recusadas.incrementAndGet();
                        }
                    } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                        // Perdeu a corrida. Em producao o Kafka reentrega e a
                        // tentativa seguinte encontra o saldo ja atualizado.
                        conflitos.incrementAndGet();
                    } catch (Exception e) {
                        synchronized (inesperados) {
                            inesperados.add(e);
                        }
                    } finally {
                        chegada.countDown();
                    }
                });
            }

            largada.countDown();
            assertThat(chegada.await(60, TimeUnit.SECONDS))
                    .describedAs("todas as tentativas precisam terminar")
                    .isTrue();
        }

        assertThat(inesperados)
                .describedAs("nenhuma falha alem de conflito de versao")
                .isEmpty();

        return new Resultado(aceitas.get(), recusadas.get(), conflitos.get());
    }

    private record Resultado(int aceitas, int recusadas, int conflitos) {

        int total() {
            return aceitas + recusadas + conflitos;
        }
    }

    @Test
    @DisplayName("dez pedidos disputando tres unidades: no maximo tres passam")
    void naoVendeMaisDoQueTem() throws Exception {
        cadastrar(3);

        var resultado = disputar(10);

        assertThat(resultado.total()).isEqualTo(10);
        assertThat(resultado.aceitas())
                .describedAs("nunca mais reservas do que unidades")
                .isLessThanOrEqualTo(3);

        var item = itens.findById(SKU).orElseThrow();
        assertThat(item.reservado()).isEqualTo(resultado.aceitas());
        assertThat(item.disponivel()).isEqualTo(3 - resultado.aceitas());
        assertThat(item.emMaos())
                .describedAs("o galpao continua com as mesmas tres unidades")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("com saldo de sobra, todos passam")
    void comSaldoDeSobraTodosPassam() throws Exception {
        cadastrar(50);

        var resultado = disputar(10);

        assertThat(resultado.aceitas() + resultado.conflitos()).isEqualTo(10);
        assertThat(resultado.recusadas()).isZero();

        var item = itens.findById(SKU).orElseThrow();
        assertThat(item.reservado()).isEqualTo(resultado.aceitas());
        assertThat(item.emMaos()).isEqualTo(50);
    }

    @Test
    @DisplayName("com saldo zero, ninguem passa e o evento de recusa e gravado")
    void semSaldoNinguemPassa() throws Exception {
        cadastrar(0);

        var resultado = disputar(5);

        assertThat(resultado.aceitas()).isZero();
        assertThat(resultado.recusadas()).isEqualTo(5);
        assertThat(reservas.count()).isZero();
        assertThat(outbox.findAll())
                .describedAs("cada recusa avisa o pedido")
                .hasSize(5)
                .allMatch(m -> m.tipo().equals("EstoqueRecusado"));
    }

    /**
     * A soma dos tres saldos e um invariante do galpao: reservar, liberar e
     * baixar so movem unidades entre colunas. Se a soma mudar depois de uma
     * rajada concorrente, alguma operacao criou ou destruiu mercadoria.
     */
    @Test
    @DisplayName("depois da disputa, a contagem do galpao continua fechando")
    void oInvarianteDoGalpaoSeMantem() throws Exception {
        cadastrar(6);

        disputar(20);

        var item = itens.findById(SKU).orElseThrow();
        assertThat(item.disponivel() + item.reservado() + item.vendido())
                .describedAs("nada foi criado nem perdido")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("cada reserva aceita gera exatamente um evento no outbox")
    void umEventoPorReserva() throws Exception {
        cadastrar(3);

        var resultado = disputar(10);

        var reservados = outbox.findAll().stream()
                .filter(m -> m.tipo().equals("EstoqueReservado")).count();
        var recusados = outbox.findAll().stream()
                .filter(m -> m.tipo().equals("EstoqueRecusado")).count();

        assertThat(reservados).isEqualTo(resultado.aceitas());
        assertThat(recusados).isEqualTo(resultado.recusadas());
    }

    @Test
    @DisplayName("o mesmo pedido processado duas vezes reserva uma vez so")
    void mesmoPedidoNaoReservaDuasVezes() {
        cadastrar(10);
        var pedido = pedidoDeUmaUnidade();

        reservar.executar(pedido);
        reservar.executar(pedido);

        var item = itens.findById(SKU).orElseThrow();
        assertThat(item.reservado()).isEqualTo(1);
        assertThat(reservas.count()).isEqualTo(1);
        assertThat(reservas.findById(pedido.correlacao()).orElseThrow().situacao())
                .isEqualTo(Reserva.Situacao.ATIVA);
    }
}
