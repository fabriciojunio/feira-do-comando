package br.com.feira.consulta;

import br.com.feira.consulta.projecao.PedidoLidoRepository;
import br.com.feira.consulta.projecao.EventosPendentes;
import br.com.feira.consulta.projecao.ConsumidorDeEventos;
import br.com.feira.consulta.projecao.EventoPendente;
import br.com.feira.contratos.Dinheiro;
import br.com.feira.contratos.Evento;
import br.com.feira.contratos.ItemDoPedido;
import br.com.feira.contratos.PagamentoAprovado;
import br.com.feira.contratos.EstoqueReservado;
import br.com.feira.contratos.PedidoConfirmado;
import br.com.feira.contratos.PedidoCriado;
import br.com.feira.contratos.Topicos;
import br.com.feira.plataforma.mensageria.MapeadorDeEventos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O servico inteiro no ar, com Kafka e MongoDB de verdade.
 *
 * <p>O que este teste cobre e que nenhum teste de unidade cobre: o evento
 * publicado no topico chega ao consumidor, e desserializado pelo mesmo
 * mapeador que os outros servicos usam, atravessa a projecao e sai pela API
 * como resposta HTTP.
 *
 * <p>Nem o Kafka nem o Mongo exigem Docker: um vem do
 * {@code spring-kafka-test} e o outro e iniciado pelo proprio teste.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@EmbeddedKafka(
        partitions = 1,
        topics = {Topicos.PEDIDOS, Topicos.ESTOQUE, Topicos.PAGAMENTOS},
        brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
@DisplayName("Projecao de ponta a ponta")
class FluxoDaProjecaoTest {

    private static final Duration ESPERA = Duration.ofSeconds(30);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private MapeadorDeEventos mapeador;

    @Autowired
    private PedidoLidoRepository repositorio;

    @Autowired
    private EventosPendentes pendentes;

    @Autowired
    private ConsumidorDeEventos consumidor;

    private UUID pedidoId;
    private Instant agora;

    @BeforeEach
    void limpar() {
        repositorio.deleteAll();
        pendentes.deleteAll();
        pedidoId = UUID.randomUUID();
        agora = Instant.now();
    }

    /** Publica com o id do pedido como chave, que e o que garante a ordem. */
    private void publicar(String topico, Evento evento) {
        kafka.send(topico, evento.correlacao().toString(), mapeador.paraJson(evento));
    }

    @Test
    @DisplayName("o evento publicado no topico vira documento e sai pela API")
    void doTopicoAteAResposta() throws Exception {
        publicar(Topicos.PEDIDOS, new PedidoCriado(
                UUID.randomUUID(), pedidoId, agora, Evento.VERSAO_ATUAL,
                "cliente-1",
                List.of(new ItemDoPedido("CAF-500", "Cafe torrado", 2, new Dinheiro(2490))),
                new Dinheiro(4980)));

        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(repositorio.findById(pedidoId)).isPresent());

        mvc.perform(get("/api/v1/consulta/pedidos/{id}", pedidoId)
                        .header("X-Cliente-Id", "cliente-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECEBIDO"))
                .andExpect(jsonPath("$.itens[0].sku").value("CAF-500"))
                .andExpect(jsonPath("$.linhaDoTempo[0].o_que").value("Pedido recebido"));
    }

    /**
     * A razao de o servico existir: hoje esta resposta exigiria uma chamada ao
     * servico de pedidos e outra ao de pagamentos, juntadas no navegador.
     */
    @Test
    @DisplayName("uma leitura devolve pedido, cobranca e linha do tempo juntos")
    void tudoNumaLeitura() throws Exception {
        publicar(Topicos.PEDIDOS, new PedidoCriado(
                UUID.randomUUID(), pedidoId, agora, Evento.VERSAO_ATUAL, "cliente-1",
                List.of(new ItemDoPedido("CAF-500", "Cafe", 2, new Dinheiro(2490))),
                new Dinheiro(4980)));
        publicar(Topicos.ESTOQUE, new EstoqueReservado(
                UUID.randomUUID(), pedidoId, agora.plusSeconds(1),
                Evento.VERSAO_ATUAL, UUID.randomUUID()));
        publicar(Topicos.PAGAMENTOS, new PagamentoAprovado(
                UUID.randomUUID(), pedidoId, agora.plusSeconds(2), Evento.VERSAO_ATUAL,
                UUID.randomUUID(), "tx-abc123", new Dinheiro(4980)));
        publicar(Topicos.PEDIDOS, new PedidoConfirmado(
                UUID.randomUUID(), pedidoId, agora.plusSeconds(3),
                Evento.VERSAO_ATUAL, new Dinheiro(4980)));

        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(repositorio.findById(pedidoId))
                        .hasValueSatisfying(p ->
                                assertThat(p.getStatus()).isEqualTo("CONFIRMADO")));

        mvc.perform(get("/api/v1/consulta/pedidos/{id}", pedidoId)
                        .header("X-Cliente-Id", "cliente-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.situacaoDaCobranca").value("APROVADA"))
                .andExpect(jsonPath("$.referenciaExterna").value("tx-abc123"))
                .andExpect(jsonPath("$.linhaDoTempo.length()").value(4));
    }

    @Test
    @DisplayName("eventos de outros topicos antes da criacao sobrevivem e sao aplicados uma vez")
    void reprocessaEventoAntesDoPedido() {
        var reserva = new EstoqueReservado(UUID.randomUUID(), pedidoId, agora.plusSeconds(1),
                Evento.VERSAO_ATUAL, UUID.randomUUID());
        var pagamento = new PagamentoAprovado(UUID.randomUUID(), pedidoId, agora.plusSeconds(2),
                Evento.VERSAO_ATUAL, UUID.randomUUID(), "tx-antes", new Dinheiro(4980));
        publicar(Topicos.ESTOQUE, reserva);
        publicar(Topicos.PAGAMENTOS, pagamento);
        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(pendentes.findByPedidoIdOrderByOcorridoEmAsc(pedidoId)).hasSize(2));

        publicar(Topicos.PEDIDOS, new PedidoCriado(
                UUID.randomUUID(), pedidoId, agora, Evento.VERSAO_ATUAL, "cliente-1",
                List.of(new ItemDoPedido("CAF-500", "Cafe", 2, new Dinheiro(2490))),
                new Dinheiro(4980)));
        publicar(Topicos.PEDIDOS, new PedidoConfirmado(
                UUID.randomUUID(), pedidoId, agora.plusSeconds(3),
                Evento.VERSAO_ATUAL, new Dinheiro(4980)));

        await().atMost(ESPERA).untilAsserted(() -> {
            assertThat(repositorio.findById(pedidoId)).hasValueSatisfying(p -> {
                assertThat(p.getStatus()).isEqualTo("CONFIRMADO");
                assertThat(p.getSituacaoDaCobranca()).isEqualTo("APROVADA");
                assertThat(p.getLinhaDoTempo()).hasSize(4);
            });
            assertThat(pendentes.findByPedidoIdOrderByOcorridoEmAsc(pedidoId)).isEmpty();
        });

        // Simula uma queda entre a aplicacao e a remocao do pendente.
        pendentes.save(new EventoPendente(reserva.id(), pedidoId, reserva.ocorridoEm(),
                mapeador.paraJson(reserva)));
        consumidor.reprocessarPendentes();
        assertThat(repositorio.findById(pedidoId).orElseThrow().getLinhaDoTempo()).hasSize(4);
        assertThat(pendentes.findByPedidoIdOrderByOcorridoEmAsc(pedidoId)).isEmpty();
    }

    /**
     * Publicar a mesma mensagem duas vezes reproduz o que o Kafka faz depois
     * de um rebalanceamento de particao.
     */
    @Test
    @DisplayName("mensagem entregue duas vezes nao duplica a linha do tempo")
    void entregaRepetida() {
        var criado = new PedidoCriado(
                UUID.randomUUID(), pedidoId, agora, Evento.VERSAO_ATUAL, "cliente-1",
                List.of(new ItemDoPedido("CAF-500", "Cafe", 1, new Dinheiro(2490))),
                new Dinheiro(2490));

        publicar(Topicos.PEDIDOS, criado);
        publicar(Topicos.PEDIDOS, criado);
        publicar(Topicos.PEDIDOS, criado);

        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(repositorio.findById(pedidoId)).isPresent());

        // Espera o suficiente para as tres entregas terem sido consumidas.
        await().during(Duration.ofSeconds(2)).atMost(ESPERA).untilAsserted(() ->
                assertThat(repositorio.findById(pedidoId))
                        .hasValueSatisfying(p ->
                                assertThat(p.getLinhaDoTempo()).hasSize(1)));
    }

    @Test
    @DisplayName("a listagem so devolve o que e do cliente que perguntou")
    void listagemPorCliente() throws Exception {
        publicar(Topicos.PEDIDOS, new PedidoCriado(
                UUID.randomUUID(), pedidoId, agora, Evento.VERSAO_ATUAL, "cliente-1",
                List.of(new ItemDoPedido("CAF-500", "Cafe", 1, new Dinheiro(2490))),
                new Dinheiro(2490)));

        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(repositorio.count()).isEqualTo(1));

        mvc.perform(get("/api/v1/consulta/pedidos").header("X-Cliente-Id", "cliente-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mvc.perform(get("/api/v1/consulta/pedidos").header("X-Cliente-Id", "outro-cliente"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    /**
     * Id de pedido aparece em URL. Sem a checagem de dono, saber o id
     * bastaria para ler o pedido de outra pessoa.
     */
    @Test
    @DisplayName("pedir o pedido de outro cliente devolve 404, e nao o documento")
    void naoVazaEntreClientes() throws Exception {
        publicar(Topicos.PEDIDOS, new PedidoCriado(
                UUID.randomUUID(), pedidoId, agora, Evento.VERSAO_ATUAL, "cliente-1",
                List.of(new ItemDoPedido("CAF-500", "Cafe", 1, new Dinheiro(2490))),
                new Dinheiro(2490)));

        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(repositorio.findById(pedidoId)).isPresent());

        mvc.perform(get("/api/v1/consulta/pedidos/{id}", pedidoId)
                        .header("X-Cliente-Id", "cliente-2"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("o teto de itens por pagina segura pedido exagerado")
    void tetoDePagina() throws Exception {
        mvc.perform(get("/api/v1/consulta/pedidos")
                        .header("X-Cliente-Id", "cliente-1")
                        .param("tamanho", "1000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tamanho").value(50));
    }
}
