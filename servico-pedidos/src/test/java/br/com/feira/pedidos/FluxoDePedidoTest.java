package br.com.feira.pedidos;

import br.com.feira.contratos.Dinheiro;
import br.com.feira.contratos.EstoqueRecusado;
import br.com.feira.contratos.Evento;
import br.com.feira.contratos.PagamentoAprovado;
import br.com.feira.contratos.PagamentoRecusado;
import br.com.feira.contratos.PedidoCriado;
import br.com.feira.contratos.ReservaLiberada;
import br.com.feira.contratos.EstoqueReservado;
import br.com.feira.contratos.Topicos;
import br.com.feira.pedidos.dominio.StatusDoPedido;
import br.com.feira.plataforma.outbox.OutboxRepository;
import br.com.feira.pedidos.infraestrutura.persistencia.PedidoRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sobe a aplicacao inteira, com PostgreSQL e Kafka de verdade, e percorre o
 * caminho completo de um pedido.
 *
 * <p>Nenhum dos dois exige Docker: o Postgres e um processo iniciado pelo
 * proprio teste, e o Kafka e o broker embutido do {@code spring-kafka-test}.
 * Isso importa porque o teste que prova o outbox funcionando de ponta a ponta
 * so vale se for executado a cada build, e nao quando alguem lembra de subir a
 * infraestrutura.
 *
 * <p>O que este teste cobre e que nenhum teste de unidade cobre: a mensagem
 * gravada no outbox chega mesmo ao topico, o consumidor a le, a saga avanca, e
 * a transacao amarra tudo isso.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
// O Spring Boot desliga metricas e rastreamento em teste por padrao, para nao
// poluir o registro global. Sem esta anotacao, /actuator/prometheus nem existe
// no contexto e a verificacao de metrica passaria a testar nada.
@org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability
@EmbeddedKafka(
        partitions = 1,
        topics = {Topicos.PEDIDOS, Topicos.ESTOQUE, Topicos.PAGAMENTOS,
                Topicos.PEDIDOS + ".dlq", Topicos.ESTOQUE + ".dlq", Topicos.PAGAMENTOS + ".dlq"},
        brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
@DisplayName("Fluxo do pedido, de ponta a ponta")
class FluxoDePedidoTest {

    private static final Duration ESPERA = Duration.ofSeconds(20);
    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry propriedades) throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        propriedades.add("spring.datasource.url",
                () -> postgres.getJdbcUrl("postgres", "postgres"));
        propriedades.add("spring.datasource.username", () -> "postgres");
        propriedades.add("spring.datasource.password", () -> "");
        propriedades.add("spring.kafka.bootstrap-servers", () -> "${spring.embedded.kafka.brokers}");

        // Publica rapido para o teste nao ficar esperando meio segundo por
        // mensagem. Em producao o padrao maior economiza consulta ao banco.
        propriedades.add("feira.outbox.intervalo-ms", () -> "100");

        // A varredura de saga fica desligada: ela roda a cada minuto e nao tem
        // relacao com o que este teste verifica.
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
    private ObjectMapper json;

    /**
     * O mesmo mapeador que o servico usa nos eventos. Serializar aqui com o
     * mapeador da API produziria um JSON diferente, e o teste passaria a
     * exercitar uma combinacao que nao existe em producao.
     */
    @Autowired
    private br.com.feira.plataforma.mensageria.MapeadorDeEventos mapeador;

    @Autowired
    private PedidoRepository pedidos;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private TransactionTemplate transacao;

    @Autowired
    private br.com.feira.pedidos.infraestrutura.persistencia.ChaveDeIdempotenciaRepository chaves;

    @Autowired
    private br.com.feira.plataforma.inbox.EventoProcessadoRepository processados;

    /**
     * A ordem importa: chaves_idempotencia aponta para pedidos, entao apagar
     * pedido antes viola a chave estrangeira. O erro nao e do teste, e o banco
     * defendendo a integridade exatamente como deve.
     */
    @BeforeEach
    void limpar() {
        transacao.executeWithoutResult(status -> {
            chaves.deleteAllInBatch();
            processados.deleteAllInBatch();
            outbox.deleteAllInBatch();
            pedidos.deleteAllInBatch();
        });
    }

    private static final String CORPO_DO_PEDIDO = """
            {
              "itens": [
                {"sku": "CAF-500", "descricao": "Cafe torrado 500g",
                 "quantidade": 2, "precoUnitario": "24.90"},
                {"sku": "ACU-1KG", "descricao": "Acucar 1kg",
                 "quantidade": 1, "precoUnitario": "6.20"}
              ]
            }
            """;

    private UUID criarPedido(String cliente, String chave) throws Exception {
        var requisicao = post("/api/v1/pedidos")
                .header("X-Cliente-Id", cliente)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CORPO_DO_PEDIDO);
        if (chave != null) {
            requisicao = requisicao.header("Idempotency-Key", chave);
        }

        var corpo = mvc.perform(requisicao)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        return UUID.fromString(json.readTree(corpo).get("id").asText());
    }

    private void publicar(Evento evento, String topico) throws Exception {
        kafka.send(topico, evento.correlacao().toString(), mapeador.paraJson(evento)).get();
    }

    private void esperarStatus(UUID pedidoId, StatusDoPedido esperado) {
        await().atMost(ESPERA).pollInterval(Duration.ofMillis(150)).untilAsserted(() ->
                assertThat(pedidos.findById(pedidoId).orElseThrow().status()).isEqualTo(esperado));
    }

    @Nested
    @DisplayName("na criacao pela API")
    class NaCriacao {

        @Test
        @DisplayName("cria o pedido, calcula o total e devolve o Location")
        void criaPedido() throws Exception {
            mvc.perform(post("/api/v1/pedidos")
                            .header("X-Cliente-Id", "cliente-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CORPO_DO_PEDIDO))
                    .andExpect(status().isCreated())
                    .andExpect(header().exists("Location"))
                    .andExpect(jsonPath("$.status").value("RECEBIDO"))
                    .andExpect(jsonPath("$.totalCentavos").value(5600))
                    .andExpect(jsonPath("$.total").value("R$ 56,00"))
                    .andExpect(jsonPath("$.itens.length()").value(2));
        }

        @Test
        @DisplayName("a mesma chave de idempotencia devolve o mesmo pedido com 200")
        void chaveDeIdempotencia() throws Exception {
            var chave = "chave-" + UUID.randomUUID();
            var primeiro = criarPedido("cliente-1", chave);

            var corpo = mvc.perform(post("/api/v1/pedidos")
                            .header("X-Cliente-Id", "cliente-1")
                            .header("Idempotency-Key", chave)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CORPO_DO_PEDIDO))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(UUID.fromString(json.readTree(corpo).get("id").asText()))
                    .isEqualTo(primeiro);
            assertThat(pedidos.count()).isEqualTo(1);
        }

        /**
         * A chave e por cliente. Dois clientes podem escolher o mesmo texto por
         * acaso, e um nao pode receber o pedido do outro.
         */
        @Test
        @DisplayName("a mesma chave em clientes diferentes cria pedidos diferentes")
        void chaveEscopadaPorCliente() throws Exception {
            var chave = "mesma-chave";
            var deUm = criarPedido("cliente-1", chave);
            var deOutro = criarPedido("cliente-2", chave);

            assertThat(deUm).isNotEqualTo(deOutro);
            assertThat(pedidos.count()).isEqualTo(2);
        }

        @Test
        @DisplayName("recusa pedido sem itens, no formato Problem Details")
        void recusaPedidoVazio() throws Exception {
            mvc.perform(post("/api/v1/pedidos")
                            .header("X-Cliente-Id", "cliente-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"itens\": []}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://feira.dev/erros/dado-invalido"))
                    .andExpect(jsonPath("$.codigo").value("DADO_INVALIDO"))
                    .andExpect(jsonPath("$.title").exists())
                    .andExpect(jsonPath("$.status").value(400));
        }

        @Test
        @DisplayName("recusa requisicao sem o cabecalho de cliente")
        void exigeCliente() throws Exception {
            mvc.perform(post("/api/v1/pedidos")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(CORPO_DO_PEDIDO))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.codigo").value("CABECALHO_OBRIGATORIO"));
        }

        @Test
        @DisplayName("um cliente nao enxerga o pedido de outro")
        void naoVazaEntreClientes() throws Exception {
            var pedido = criarPedido("cliente-1", null);

            mvc.perform(get("/api/v1/pedidos/" + pedido).header("X-Cliente-Id", "cliente-2"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.codigo").value("NAO_ENCONTRADO"));
        }
    }

    @Nested
    @DisplayName("no outbox")
    class NoOutbox {

        @Test
        @DisplayName("grava o evento de abertura na mesma transacao do pedido")
        void gravaNaMesmaTransacao() throws Exception {
            var pedidoId = criarPedido("cliente-1", null);

            var mensagens = outbox.findAll();
            assertThat(mensagens).hasSize(1);
            assertThat(mensagens.get(0).chave()).isEqualTo(pedidoId.toString());
            assertThat(mensagens.get(0).tipo()).isEqualTo("PedidoCriado");
            assertThat(mensagens.get(0).topico()).isEqualTo(Topicos.PEDIDOS);
        }

        @Test
        @DisplayName("o publicador entrega ao Kafka e marca a mensagem")
        void publicaEMarca() throws Exception {
            var pedidoId = criarPedido("cliente-1", null);

            await().atMost(ESPERA).untilAsserted(() -> {
                var mensagem = outbox.findAll().get(0);
                assertThat(mensagem.publicada()).isTrue();
                assertThat(mensagem.publicadaEm()).isNotNull();
                assertThat(mensagem.tentativas()).isZero();
            });

            var carga = outbox.findAll().get(0).carga();
            var evento = mapeador.deJson(carga);
            assertThat(evento).isInstanceOf(PedidoCriado.class);
            assertThat(evento.correlacao()).isEqualTo(pedidoId);
            assertThat(((PedidoCriado) evento).total()).isEqualTo(Dinheiro.de("56.00"));
        }
    }

    @Nested
    @DisplayName("na saga completa")
    class NaSagaCompleta {

        @Test
        @DisplayName("reserva e pagamento aprovado confirmam o pedido")
        void caminhoFeliz() throws Exception {
            var pedidoId = criarPedido("cliente-1", null);

            publicar(EstoqueReservado.de(pedidoId, UUID.randomUUID(),
                    java.time.Instant.now()), Topicos.ESTOQUE);
            esperarStatus(pedidoId, StatusDoPedido.ESTOQUE_RESERVADO);

            publicar(PagamentoAprovado.de(pedidoId, UUID.randomUUID(), "tx-1",
                    Dinheiro.de("56.00"), java.time.Instant.now()), Topicos.PAGAMENTOS);
            esperarStatus(pedidoId, StatusDoPedido.CONFIRMADO);

            mvc.perform(get("/api/v1/pedidos/" + pedidoId).header("X-Cliente-Id", "cliente-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CONFIRMADO"))
                    .andExpect(jsonPath("$.situacao").value("Pedido confirmado"));
        }

        @Test
        @DisplayName("falta de estoque cancela sem compensar")
        void semEstoque() throws Exception {
            var pedidoId = criarPedido("cliente-1", null);

            publicar(EstoqueRecusado.de(pedidoId, java.util.List.of("CAF-500"), "saldo zero",
                    java.time.Instant.now()), Topicos.ESTOQUE);

            esperarStatus(pedidoId, StatusDoPedido.CANCELADO);

            mvc.perform(get("/api/v1/pedidos/" + pedidoId).header("X-Cliente-Id", "cliente-1"))
                    .andExpect(jsonPath("$.motivoDoCancelamento").value("SEM_ESTOQUE"))
                    .andExpect(jsonPath("$.detalheDoCancelamento").value(
                            org.hamcrest.Matchers.containsString("CAF-500")));
        }

        @Test
        @DisplayName("pagamento recusado compensa a reserva antes de cancelar")
        void pagamentoRecusadoCompensa() throws Exception {
            var pedidoId = criarPedido("cliente-1", null);
            var reserva = UUID.randomUUID();

            publicar(EstoqueReservado.de(pedidoId, reserva, java.time.Instant.now()),
                    Topicos.ESTOQUE);
            esperarStatus(pedidoId, StatusDoPedido.ESTOQUE_RESERVADO);

            publicar(PagamentoRecusado.de(pedidoId, UUID.randomUUID(),
                            PagamentoRecusado.Motivo.SALDO_INSUFICIENTE, java.time.Instant.now()),
                    Topicos.PAGAMENTOS);
            esperarStatus(pedidoId, StatusDoPedido.CANCELANDO);

            // O servico publicou o pedido de liberacao. Aqui o estoque responde.
            await().atMost(ESPERA).untilAsserted(() ->
                    assertThat(outbox.findAll())
                            .anyMatch(m -> m.tipo().equals("ReservaLiberada")));

            publicar(ReservaLiberada.de(pedidoId, reserva, "compensacao",
                    java.time.Instant.now()), Topicos.ESTOQUE);
            esperarStatus(pedidoId, StatusDoPedido.CANCELADO);
        }

        /**
         * A entrega do Kafka e ao menos uma vez. Publicar o mesmo evento duas
         * vezes tem que ser inofensivo, e isso e o inbox trabalhando.
         */
        @Test
        @DisplayName("o mesmo evento publicado duas vezes so tem efeito uma vez")
        void eventoRepetidoNaoDuplica() throws Exception {
            var pedidoId = criarPedido("cliente-1", null);
            var reserva = EstoqueReservado.de(pedidoId, UUID.randomUUID(),
                    java.time.Instant.now());

            publicar(reserva, Topicos.ESTOQUE);
            esperarStatus(pedidoId, StatusDoPedido.ESTOQUE_RESERVADO);

            long versaoAntes = pedidos.findById(pedidoId).orElseThrow().versao();

            publicar(reserva, Topicos.ESTOQUE);
            Thread.sleep(1500);

            var depois = pedidos.findById(pedidoId).orElseThrow();
            assertThat(depois.status()).isEqualTo(StatusDoPedido.ESTOQUE_RESERVADO);
            assertThat(depois.versao())
                    .describedAs("evento repetido nao pode gravar o pedido de novo")
                    .isEqualTo(versaoAntes);
        }

        @Test
        @DisplayName("mensagem ilegivel vai para a fila de mortas sem travar a particao")
        void mensagemIlegivelNaoTrava() throws Exception {
            var pedidoId = criarPedido("cliente-1", null);

            kafka.send(Topicos.ESTOQUE, pedidoId.toString(), "isto nao e json").get();

            // A particao precisa continuar andando: o proximo evento valido
            // tem que ser processado normalmente.
            publicar(EstoqueReservado.de(pedidoId, UUID.randomUUID(), java.time.Instant.now()),
                    Topicos.ESTOQUE);

            esperarStatus(pedidoId, StatusDoPedido.ESTOQUE_RESERVADO);
        }
    }

    @Nested
    @DisplayName("no cancelamento pela API")
    class NoCancelamento {

        @Test
        @DisplayName("cancela antes da reserva")
        void cancelaAntesDaReserva() throws Exception {
            var pedidoId = criarPedido("cliente-1", null);

            mvc.perform(delete("/api/v1/pedidos/" + pedidoId).header("X-Cliente-Id", "cliente-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELADO"))
                    .andExpect(jsonPath("$.motivoDoCancelamento").value("PEDIDO_DO_CLIENTE"));
        }

        @Test
        @DisplayName("recusa cancelar pedido ja confirmado, com 409")
        void naoCancelaConfirmado() throws Exception {
            var pedidoId = criarPedido("cliente-1", null);

            publicar(EstoqueReservado.de(pedidoId, UUID.randomUUID(), java.time.Instant.now()),
                    Topicos.ESTOQUE);
            esperarStatus(pedidoId, StatusDoPedido.ESTOQUE_RESERVADO);
            publicar(PagamentoAprovado.de(pedidoId, UUID.randomUUID(), "tx-1",
                    Dinheiro.de("56.00"), java.time.Instant.now()), Topicos.PAGAMENTOS);
            esperarStatus(pedidoId, StatusDoPedido.CONFIRMADO);

            mvc.perform(delete("/api/v1/pedidos/" + pedidoId).header("X-Cliente-Id", "cliente-1"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.codigo").value("CANCELAMENTO_NAO_PERMITIDO"))
                    .andExpect(jsonPath("$.statusDoPedido").value("CONFIRMADO"));
        }

        @Test
        @DisplayName("um cliente nao cancela o pedido de outro")
        void naoCancelaDeOutro() throws Exception {
            var pedidoId = criarPedido("cliente-1", null);

            mvc.perform(delete("/api/v1/pedidos/" + pedidoId).header("X-Cliente-Id", "cliente-2"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("na listagem")
    class NaListagem {

        @Test
        @DisplayName("pagina os pedidos do cliente, do mais novo para o mais antigo")
        void pagina() throws Exception {
            criarPedido("cliente-1", null);
            criarPedido("cliente-1", null);
            criarPedido("cliente-2", null);

            mvc.perform(get("/api/v1/pedidos")
                            .header("X-Cliente-Id", "cliente-1")
                            .param("tamanho", "10"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2))
                    .andExpect(jsonPath("$.conteudo.length()").value(2));
        }

        @Test
        @DisplayName("respeita o teto do tamanho da pagina")
        void limitaTamanho() throws Exception {
            criarPedido("cliente-1", null);

            mvc.perform(get("/api/v1/pedidos")
                            .header("X-Cliente-Id", "cliente-1")
                            .param("tamanho", "5000"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tamanho").value(100));
        }
    }

    @Nested
    @DisplayName("na operacao")
    class NaOperacao {

        @Test
        @DisplayName("a sonda de saude responde")
        void saude() throws Exception {
            mvc.perform(get("/actuator/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }

        @Test
        @DisplayName("as metricas do outbox estao expostas")
        void metricas() throws Exception {
            criarPedido("cliente-1", null);

            await().atMost(ESPERA).untilAsserted(() ->
                    mvc.perform(get("/actuator/prometheus"))
                            .andExpect(status().isOk())
                            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                    .content().string(
                                            org.hamcrest.Matchers.containsString("feira_outbox"))));
        }

        @Test
        @DisplayName("a documentacao da API esta publicada")
        void documentacao() throws Exception {
            mvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.paths['/api/v1/pedidos']").exists());
        }
    }
}
