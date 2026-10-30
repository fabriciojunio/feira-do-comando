package br.com.feira.pagamentos;

import br.com.feira.contratos.Dinheiro;
import br.com.feira.contratos.EstoqueReservado;
import br.com.feira.contratos.ItemDoPedido;
import br.com.feira.contratos.PedidoCriado;
import br.com.feira.contratos.Topicos;
import br.com.feira.pagamentos.dominio.Cobranca;
import br.com.feira.pagamentos.infraestrutura.persistencia.CobrancaRepository;
import br.com.feira.plataforma.mensageria.MapeadorDeEventos;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * O EstoqueReservado pode chegar antes do PedidoCriado: são tópicos diferentes
 * e o Kafka não garante ordem entre eles. O consumidor espera a cobrança em
 * repetição e precisa continuar lendo o outro tópico enquanto isso, senão o
 * pedido fica parado e o evento acaba na fila de mortas.
 */
@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {Topicos.PEDIDOS, Topicos.ESTOQUE, Topicos.PAGAMENTOS,
                Topicos.PEDIDOS + ".dlq", Topicos.ESTOQUE + ".dlq", Topicos.PAGAMENTOS + ".dlq"},
        brokerProperties = {"listeners=PLAINTEXT://localhost:0", "port=0"})
@DisplayName("Ordem dos eventos entre tópicos")
class OrdemDosEventosTest {

    /** Menos que os 30 segundos de repetição: se cobrar, cobrou antes da fila de mortas. */
    private static final Duration ESPERA = Duration.ofSeconds(20);
    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry propriedades) throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        propriedades.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        propriedades.add("spring.datasource.username", () -> "postgres");
        propriedades.add("spring.datasource.password", () -> "");
        propriedades.add("spring.kafka.bootstrap-servers", () -> "${spring.embedded.kafka.brokers}");
        propriedades.add("feira.pagamentos.percentual-de-recusa", () -> "0");
    }

    @AfterAll
    static void derrubar() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private MapeadorDeEventos mapeador;

    @Autowired
    private CobrancaRepository cobrancas;

    @Test
    @DisplayName("cobra mesmo quando o estoque reservado chega antes do pedido criado")
    void cobraComEstoqueAntesDoPedido() throws Exception {
        UUID pedido = UUID.randomUUID();
        Instant agora = Instant.now();
        var item = new ItemDoPedido("CAF-500", "Café torrado 500g (dados simulados)", 2, Dinheiro.de("24.90"));

        kafka.send(Topicos.ESTOQUE, pedido.toString(),
                mapeador.paraJson(EstoqueReservado.de(pedido, UUID.randomUUID(), agora))).get();
        // Dá tempo de o consumidor pegar o estoque e entrar em repetição antes do pedido.
        Thread.sleep(2_000);
        kafka.send(Topicos.PEDIDOS, pedido.toString(),
                mapeador.paraJson(PedidoCriado.de(pedido, "cliente-1", List.of(item), Dinheiro.de("49.80"), agora))).get();

        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(cobrancas.findAll())
                        .filteredOn(c -> c.pedidoId().equals(pedido))
                        .singleElement()
                        .extracting(Cobranca::situacao)
                        .isEqualTo(Cobranca.Situacao.APROVADA));
    }
}
