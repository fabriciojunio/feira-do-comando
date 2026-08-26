package br.com.feira.pedidos;

import br.com.feira.contratos.Topicos;
import br.com.feira.plataforma.outbox.OutboxRepository;
import br.com.feira.pedidos.infraestrutura.persistencia.PedidoRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Vazao de criacao de pedidos sob carga concorrente.
 *
 * <p>Existe porque "aguenta X requisicoes por segundo" e uma frase que so vale
 * dita com numero medido. O numero sai daqui: PostgreSQL de verdade, Kafka de
 * verdade, a aplicacao inteira no ar, e requisicoes disparadas em paralelo por
 * threads reais.
 *
 * <p>O que este teste NAO e: um substituto para k6 ou Gatling contra um
 * ambiente parecido com producao. Ele roda contra um Postgres embutido, na
 * mesma JVM, atraves do MockMvc, e portanto nao mede rede, serializacao de
 * socket nem o custo do proxy. O numero absoluto por isso vale pouco; o que
 * vale e o piso e a tendencia. Uma regressao que dobre o custo por pedido
 * aparece aqui, no build, e nao no dia da apresentacao.
 *
 * <p>O piso e deliberadamente folgado. O corredor do GitHub Actions e mais
 * lento e mais variavel que qualquer maquina de desenvolvimento, e um teste de
 * desempenho que falha de vez em quando por barulho do ambiente e pior que
 * teste de desempenho nenhum: ensina o time a ignorar build vermelho.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EmbeddedKafka(
        partitions = 1,
        topics = {Topicos.PEDIDOS, Topicos.ESTOQUE, Topicos.PAGAMENTOS,
                Topicos.PEDIDOS + ".dlq", Topicos.ESTOQUE + ".dlq", Topicos.PAGAMENTOS + ".dlq"},
        brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
@DisplayName("Vazao sob carga")
class VazaoDePedidosTest {

    /**
     * Piso de vazao. Ver o comentario da classe: folgado de proposito.
     */
    private static final double PEDIDOS_POR_SEGUNDO_MINIMO = 20.0;

    private static final int THREADS = 8;
    private static final int PEDIDOS_POR_THREAD = 15;
    private static final int TOTAL = THREADS * PEDIDOS_POR_THREAD;

    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry propriedades) throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        propriedades.add("spring.datasource.url",
                () -> postgres.getJdbcUrl("postgres", "postgres"));
        propriedades.add("spring.datasource.username", () -> "postgres");
        propriedades.add("spring.datasource.password", () -> "");
        propriedades.add("spring.kafka.bootstrap-servers", () -> "${spring.embedded.kafka.brokers}");
        propriedades.add("feira.outbox.intervalo-ms", () -> "100");
        propriedades.add("feira.saga.cron-varredura", () -> "-");
    }

    @AfterAll
    static void derrubar() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PedidoRepository pedidos;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private br.com.feira.pedidos.infraestrutura.persistencia.ChaveDeIdempotenciaRepository chaves;

    /**
     * A ordem importa: a chave de idempotencia referencia o pedido, entao
     * apagar o pedido primeiro viola a chave estrangeira.
     */
    @BeforeEach
    void limpar() {
        chaves.deleteAll();
        outbox.deleteAll();
        pedidos.deleteAll();
    }

    private String corpoDePedido() {
        return """
                {"itens":[{"sku":"CAF-500","descricao":"Cafe torrado e moido",
                "quantidade":1,"precoUnitario":"24.90"}]}
                """;
    }

    @Test
    @DisplayName("cria pedidos em paralelo sem perder nenhum e sem despencar de vazao")
    void vazaoDeCriacao() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        // Portao de largada: sem ele as primeiras threads terminam antes de as
        // ultimas comecarem, e o que se mede deixa de ser concorrencia.
        CountDownLatch largada = new CountDownLatch(1);
        AtomicInteger criados = new AtomicInteger();
        AtomicInteger falhas = new AtomicInteger();

        List<Callable<Void>> tarefas = new ArrayList<>();
        for (int t = 0; t < THREADS; t++) {
            tarefas.add(() -> {
                largada.await();
                for (int i = 0; i < PEDIDOS_POR_THREAD; i++) {
                    try {
                        int status = mvc.perform(post("/api/v1/pedidos")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("X-Cliente-Id", "carga")
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .content(corpoDePedido()))
                                .andReturn().getResponse().getStatus();
                        if (status == 201) {
                            criados.incrementAndGet();
                        } else {
                            falhas.incrementAndGet();
                        }
                    } catch (Exception e) {
                        falhas.incrementAndGet();
                    }
                }
                return null;
            });
        }

        List<Future<Void>> futuros = new ArrayList<>();
        for (Callable<Void> tarefa : tarefas) {
            futuros.add(executor.submit(tarefa));
        }

        long inicio = System.nanoTime();
        largada.countDown();
        for (Future<Void> futuro : futuros) {
            futuro.get(2, TimeUnit.MINUTES);
        }
        long decorridoNanos = System.nanoTime() - inicio;

        executor.shutdown();
        assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        double segundos = decorridoNanos / 1_000_000_000.0;
        double porSegundo = criados.get() / segundos;

        // O numero vai para a saida do build de proposito: e ele que permite
        // comparar duas execucoes e perceber uma regressao de desempenho.
        System.out.printf(
                "%n[vazao] %d pedidos criados em %.2fs com %d threads: %.1f pedidos/s (%.1f ms por pedido)%n",
                criados.get(), segundos, THREADS, porSegundo, 1000.0 / porSegundo);

        assertThat(falhas.get()).as("nenhuma requisicao pode falhar sob carga").isZero();
        assertThat(criados.get()).as("todos os pedidos foram aceitos").isEqualTo(TOTAL);
        assertThat(porSegundo)
                .as("vazao de criacao de pedidos")
                .isGreaterThan(PEDIDOS_POR_SEGUNDO_MINIMO);
    }

    @Test
    @DisplayName("cada pedido aceito deixa exatamente uma mensagem no outbox")
    void nenhumEventoSePerdeSobCarga() throws Exception {
        int quantidade = 40;
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        CountDownLatch largada = new CountDownLatch(1);

        List<Future<Integer>> futuros = new ArrayList<>();
        for (int i = 0; i < quantidade; i++) {
            futuros.add(executor.submit(() -> {
                largada.await();
                return mvc.perform(post("/api/v1/pedidos")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Cliente-Id", "carga")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .content(corpoDePedido()))
                        .andReturn().getResponse().getStatus();
            }));
        }

        largada.countDown();
        int aceitos = 0;
        for (Future<Integer> futuro : futuros) {
            if (futuro.get(2, TimeUnit.MINUTES) == 201) {
                aceitos++;
            }
        }
        executor.shutdown();

        assertThat(aceitos).isEqualTo(quantidade);
        assertThat(pedidos.count()).isEqualTo(quantidade);

        // A escrita do pedido e a da mensagem acontecem na mesma transacao. Se
        // sob concorrencia uma delas escapasse, a contagem nao bateria, e o
        // evento perdido so apareceria como pedido que nunca sai de RECEBIDO.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(outbox.count())
                        .as("uma mensagem de outbox por pedido, nem mais nem menos")
                        .isGreaterThanOrEqualTo(quantidade));
    }

    /**
     * A chave de idempotencia tem que segurar mesmo com as tentativas chegando
     * ao mesmo tempo, que e exatamente o que acontece quando o aplicativo do
     * cliente repete a requisicao por timeout de rede.
     */
    @Test
    @DisplayName("a mesma chave de idempotencia sob corrida cria um pedido so")
    void chaveRepetidaSobCorridaCriaUmPedidoSo() throws Exception {
        String chave = UUID.randomUUID().toString();
        int tentativas = 12;

        ExecutorService executor = Executors.newFixedThreadPool(tentativas);
        CountDownLatch largada = new CountDownLatch(1);

        List<Future<Integer>> futuros = new ArrayList<>();
        for (int i = 0; i < tentativas; i++) {
            futuros.add(executor.submit(() -> {
                largada.await();
                return mvc.perform(post("/api/v1/pedidos")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("X-Cliente-Id", "carga")
                                .header("Idempotency-Key", chave)
                                .content(corpoDePedido()))
                        .andReturn().getResponse().getStatus();
            }));
        }

        largada.countDown();
        List<Integer> respostas = new ArrayList<>();
        for (Future<Integer> futuro : futuros) {
            respostas.add(futuro.get(1, TimeUnit.MINUTES));
        }
        executor.shutdown();

        assertThat(pedidos.count())
                .as("doze tentativas simultaneas com a mesma chave nao podem virar doze pedidos")
                .isEqualTo(1);

        // O banco garantir o pedido unico nao basta: quem perdeu a corrida
        // precisa receber o pedido vencedor, e nao um erro. Ja esteve errado
        // aqui, com as perdedoras recebendo 500 porque a recuperacao acontecia
        // dentro da transacao que a propria violacao tinha condenado.
        assertThat(respostas)
                .as("toda tentativa recebe resposta de sucesso, nao erro")
                .allMatch(status -> status == 200 || status == 201);

        assertThat(respostas)
                .as("exatamente uma tentativa cria; as outras reaproveitam")
                .containsOnlyOnce(201);
    }
}
