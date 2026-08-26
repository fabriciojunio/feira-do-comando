package br.com.feira.contratos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O contrato entre os servicos e a unica coisa que eles compartilham. Se ele
 * quebra, o sistema quebra sem ninguem compilar errado, entao ele e testado
 * como codigo de producao.
 */
@DisplayName("Contratos de evento")
class ContratosTest {

    private static final Instant AGORA = Instant.parse("2026-08-25T12:00:00Z");
    private static final UUID PEDIDO = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final ObjectMapper json = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Nested
    @DisplayName("Dinheiro")
    class DoDinheiro {

        @Test
        @DisplayName("le decimal sem perder centavo")
        void leDecimal() {
            assertThat(Dinheiro.de("19.90").centavos()).isEqualTo(1990);
            assertThat(Dinheiro.de("0.01").centavos()).isEqualTo(1);
            assertThat(Dinheiro.de("1000").centavos()).isEqualTo(100000);
        }

        @Test
        @DisplayName("arredonda a terceira casa para o centavo mais proximo")
        void arredonda() {
            assertThat(Dinheiro.de("10.005").centavos()).isEqualTo(1001);
            assertThat(Dinheiro.de("10.004").centavos()).isEqualTo(1000);
        }

        @Test
        @DisplayName("soma dez centavos e vinte centavos e da exatamente trinta")
        void somaExata() {
            var soma = Dinheiro.de("0.10").mais(Dinheiro.de("0.20"));

            assertThat(soma.centavos()).isEqualTo(30);
            assertThat(soma.emReais().toPlainString()).isEqualTo("0.30");
        }

        @Test
        @DisplayName("multiplica sem estourar silenciosamente")
        void multiplicaComSeguranca() {
            assertThat(Dinheiro.de("19.90").vezes(3).centavos()).isEqualTo(5970);
            assertThat(Dinheiro.de("19.90").vezes(0)).isEqualTo(Dinheiro.ZERO);

            assertThatThrownBy(() -> new Dinheiro(Long.MAX_VALUE).vezes(2))
                    .isInstanceOf(ArithmeticException.class);
        }

        @Test
        @DisplayName("recusa valor negativo e quantidade negativa")
        void recusaNegativos() {
            assertThatThrownBy(() -> new Dinheiro(-1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Dinheiro.de("10.00").vezes(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("formata em real brasileiro")
        void formata() {
            assertThat(Dinheiro.de("1234.50").formatado()).isEqualTo("R$ 1.234,50");
            assertThat(Dinheiro.ZERO.formatado()).isEqualTo("R$ 0,00");
        }

        @Test
        @DisplayName("compara por valor")
        void compara() {
            assertThat(Dinheiro.de("10.00")).isGreaterThan(Dinheiro.de("9.99"));
            assertThat(Dinheiro.de("10.00")).isEqualTo(Dinheiro.de("10.00"));
        }
    }

    @Nested
    @DisplayName("Item do pedido")
    class DoItem {

        @Test
        @DisplayName("calcula o subtotal")
        void subtotal() {
            var item = new ItemDoPedido("CAF-500", "Cafe torrado 500g", 3, Dinheiro.de("24.90"));

            assertThat(item.subtotal().centavos()).isEqualTo(7470);
        }

        @Test
        @DisplayName("recusa sku vazio e quantidade zero")
        void recusaInvalidos() {
            assertThatThrownBy(() -> new ItemDoPedido(" ", "x", 1, Dinheiro.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ItemDoPedido("A", "x", 0, Dinheiro.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ItemDoPedido("A", "x", 1, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Serializacao")
    class DaSerializacao {

        /**
         * Ida e volta por JSON e o que de fato acontece no Kafka. Um teste que
         * so monta o objeto em memoria nao prova nada sobre o que o outro
         * servico vai conseguir ler.
         */
        @Test
        @DisplayName("pedido criado sobrevive a ida e volta por JSON")
        void pedidoCriadoIdaEVolta() throws Exception {
            var original = PedidoCriado.de(PEDIDO, "cliente-1",
                    List.of(new ItemDoPedido("CAF-500", "Cafe", 2, Dinheiro.de("24.90"))),
                    Dinheiro.de("49.80"), AGORA);

            var texto = json.writeValueAsString(original);
            var lido = json.readValue(texto, Evento.class);

            assertThat(lido).isInstanceOf(PedidoCriado.class).isEqualTo(original);
            assertThat(texto).contains("\"tipo\":\"PedidoCriado\"");
        }

        @Test
        @DisplayName("cada evento carrega o discriminador de tipo")
        void todosCarregamTipo() throws Exception {
            var eventos = List.of(
                    PedidoCriado.de(PEDIDO, "c", List.of(
                            new ItemDoPedido("A", "a", 1, Dinheiro.de("1.00"))),
                            Dinheiro.de("1.00"), AGORA),
                    EstoqueReservado.de(PEDIDO, UUID.randomUUID(), AGORA),
                    EstoqueRecusado.de(PEDIDO, List.of("A"), "sem saldo", AGORA),
                    ReservaLiberada.de(PEDIDO, UUID.randomUUID(), "pagamento recusado", AGORA),
                    PagamentoAprovado.de(PEDIDO, UUID.randomUUID(), "tx-1", Dinheiro.de("1.00"), AGORA),
                    PagamentoRecusado.de(PEDIDO, UUID.randomUUID(),
                            PagamentoRecusado.Motivo.SALDO_INSUFICIENTE, AGORA),
                    PagamentoEstornado.de(PEDIDO, UUID.randomUUID(), Dinheiro.de("1.00"), "x", AGORA),
                    PedidoConfirmado.de(PEDIDO, Dinheiro.de("1.00"), AGORA),
                    PedidoCancelado.de(PEDIDO, PedidoCancelado.Motivo.SEM_ESTOQUE, "x", AGORA));

            for (Evento evento : eventos) {
                var texto = json.writeValueAsString(evento);
                assertThat(texto)
                        .describedAs("evento %s deve carregar o tipo", evento.nome())
                        .contains("\"tipo\":\"" + evento.nome() + "\"");

                var lido = json.readValue(texto, Evento.class);
                assertThat(lido).isEqualTo(evento);
                assertThat(lido.correlacao()).isEqualTo(PEDIDO);
                assertThat(lido.versao()).isEqualTo(Evento.VERSAO_ATUAL);
                assertThat(lido.id()).isNotNull();
            }
        }

        @Test
        @DisplayName("cada evento nasce com id proprio, distinto da correlacao")
        void idDistintoDaCorrelacao() {
            var primeiro = EstoqueReservado.de(PEDIDO, UUID.randomUUID(), AGORA);
            var segundo = EstoqueReservado.de(PEDIDO, UUID.randomUUID(), AGORA);

            assertThat(primeiro.id()).isNotEqualTo(segundo.id());
            assertThat(primeiro.correlacao()).isEqualTo(segundo.correlacao());
        }
    }

    @Nested
    @DisplayName("Motivo da recusa de pagamento")
    class DoMotivo {

        @Test
        @DisplayName("so falha temporaria permite nova tentativa")
        void apenasTemporariaRetenta() {
            assertThat(PagamentoRecusado.Motivo.FALHA_TEMPORARIA.permiteNovaTentativa()).isTrue();
            assertThat(PagamentoRecusado.Motivo.SALDO_INSUFICIENTE.permiteNovaTentativa()).isFalse();
            assertThat(PagamentoRecusado.Motivo.CARTAO_INVALIDO.permiteNovaTentativa()).isFalse();
            assertThat(PagamentoRecusado.Motivo.SUSPEITA_DE_FRAUDE.permiteNovaTentativa()).isFalse();
        }
    }

    @Nested
    @DisplayName("Topicos")
    class DosTopicos {

        @Test
        @DisplayName("deriva o nome da fila de mensagens mortas")
        void derivaDlq() {
            assertThat(Topicos.dlqDe(Topicos.PEDIDOS)).isEqualTo("feira.pedidos.dlq");
            assertThat(Topicos.dlqDe(Topicos.ESTOQUE)).isEqualTo("feira.estoque.dlq");
        }
    }
}
