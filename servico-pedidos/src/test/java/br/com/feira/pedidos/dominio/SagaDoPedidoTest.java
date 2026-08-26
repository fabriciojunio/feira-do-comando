package br.com.feira.pedidos.dominio;

import br.com.feira.contratos.Dinheiro;
import br.com.feira.contratos.EstoqueRecusado;
import br.com.feira.contratos.EstoqueReservado;
import br.com.feira.contratos.ItemDoPedido;
import br.com.feira.contratos.PagamentoAprovado;
import br.com.feira.contratos.PagamentoEstornado;
import br.com.feira.contratos.PagamentoRecusado;
import br.com.feira.contratos.PedidoCancelado;
import br.com.feira.contratos.PedidoConfirmado;
import br.com.feira.contratos.ReservaLiberada;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A maquina de estados da saga, coberta caso a caso.
 *
 * Este e o teste que importa neste servico. Um sistema orientado a eventos
 * falha quase sempre pelos mesmos tres motivos, e nenhum deles aparece no
 * caminho feliz: mensagem repetida, mensagem fora de ordem e mensagem que
 * chega depois de o assunto ja estar encerrado. Cada um tem um bloco aqui.
 */
@DisplayName("Saga do pedido")
class SagaDoPedidoTest {

    private static final Instant AGORA = Instant.parse("2026-08-25T14:00:00Z");
    private static final UUID PEDIDO = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RESERVA = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PAGAMENTO = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private Pedido pedido;

    @BeforeEach
    void abrirPedido() {
        pedido = Pedido.abrir(PEDIDO, "cliente-1", List.of(
                new ItemDoPedido("CAF-500", "Cafe torrado 500g", 2, Dinheiro.de("24.90")),
                new ItemDoPedido("ACU-1KG", "Acucar 1kg", 1, Dinheiro.de("6.20"))), AGORA);
    }

    private void reservarEstoque() {
        pedido.processar(EstoqueReservado.de(PEDIDO, RESERVA, AGORA), AGORA);
    }

    @Nested
    @DisplayName("na abertura")
    class NaAbertura {

        @Test
        @DisplayName("soma o total a partir dos itens")
        void somaOTotal() {
            assertThat(pedido.total()).isEqualTo(Dinheiro.de("56.00"));
            assertThat(pedido.status()).isEqualTo(StatusDoPedido.RECEBIDO);
            assertThat(pedido.itens()).hasSize(2);
        }

        @Test
        @DisplayName("o evento de abertura carrega os itens e o total congelados")
        void eventoDeAbertura() {
            var evento = pedido.eventoDeAbertura(AGORA);

            assertThat(evento.correlacao()).isEqualTo(PEDIDO);
            assertThat(evento.clienteId()).isEqualTo("cliente-1");
            assertThat(evento.total()).isEqualTo(Dinheiro.de("56.00"));
            assertThat(evento.itens()).hasSize(2);
            assertThat(evento.itens().get(0).precoUnitario()).isEqualTo(Dinheiro.de("24.90"));
        }

        @Test
        @DisplayName("recusa pedido sem item e sem cliente")
        void recusaPedidoInvalido() {
            assertThatThrownBy(() -> Pedido.abrir(PEDIDO, "cliente", List.of(), AGORA))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ao menos um item");

            assertThatThrownBy(() -> Pedido.abrir(PEDIDO, " ", List.of(
                    new ItemDoPedido("A", "a", 1, Dinheiro.de("1.00"))), AGORA))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cliente");
        }
    }

    @Nested
    @DisplayName("no caminho feliz")
    class NoCaminhoFeliz {

        @Test
        @DisplayName("reserva de estoque leva o pedido para aguardando cobranca")
        void reservaAvanca() {
            var decisao = pedido.processar(EstoqueReservado.de(PEDIDO, RESERVA, AGORA), AGORA);

            assertThat(decisao.mudouDeEstado()).isTrue();
            assertThat(pedido.status()).isEqualTo(StatusDoPedido.ESTOQUE_RESERVADO);
            assertThat(pedido.reservaId()).isEqualTo(RESERVA);
        }

        @Test
        @DisplayName("pagamento aprovado confirma o pedido")
        void pagamentoConfirma() {
            reservarEstoque();

            var decisao = pedido.processar(PagamentoAprovado.de(
                    PEDIDO, PAGAMENTO, "tx-1", Dinheiro.de("56.00"), AGORA), AGORA);

            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CONFIRMADO);
            assertThat(pedido.pagamentoId()).isEqualTo(PAGAMENTO);
            assertThat(decisao.eventosASeguir())
                    .singleElement()
                    .isInstanceOf(PedidoConfirmado.class);
        }

        @Test
        @DisplayName("o evento de confirmacao carrega o total cobrado")
        void confirmacaoCarregaTotal() {
            reservarEstoque();

            var decisao = pedido.processar(PagamentoAprovado.de(
                    PEDIDO, PAGAMENTO, "tx-1", Dinheiro.de("56.00"), AGORA), AGORA);

            var confirmado = (PedidoConfirmado) decisao.eventosASeguir().get(0);
            assertThat(confirmado.total()).isEqualTo(Dinheiro.de("56.00"));
            assertThat(confirmado.correlacao()).isEqualTo(PEDIDO);
        }
    }

    @Nested
    @DisplayName("quando falta estoque")
    class QuandoFaltaEstoque {

        @Test
        @DisplayName("cancela direto, sem compensar o que nao aconteceu")
        void cancelaSemCompensar() {
            var decisao = pedido.processar(
                    EstoqueRecusado.de(PEDIDO, List.of("CAF-500"), "saldo zero", AGORA), AGORA);

            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CANCELADO);
            assertThat(pedido.motivoDoCancelamento()).isEqualTo(PedidoCancelado.Motivo.SEM_ESTOQUE);
            assertThat(decisao.eventosASeguir())
                    .singleElement()
                    .isInstanceOf(PedidoCancelado.class);
            assertThat(decisao.eventosASeguir())
                    .noneMatch(ReservaLiberada.class::isInstance);
        }

        @Test
        @DisplayName("o detalhe diz quais SKUs faltaram")
        void detalheListaSkus() {
            pedido.processar(EstoqueRecusado.de(PEDIDO, List.of("CAF-500", "ACU-1KG"),
                    "saldo zero", AGORA), AGORA);

            assertThat(pedido.detalheDoCancelamento()).contains("CAF-500").contains("ACU-1KG");
        }
    }

    @Nested
    @DisplayName("quando o pagamento e recusado")
    class QuandoOPagamentoERecusado {

        @Test
        @DisplayName("entra em compensacao e manda liberar a reserva")
        void compensaLiberandoReserva() {
            reservarEstoque();

            var decisao = pedido.processar(PagamentoRecusado.de(
                    PEDIDO, PAGAMENTO, PagamentoRecusado.Motivo.SALDO_INSUFICIENTE, AGORA), AGORA);

            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CANCELANDO);
            var liberacao = (ReservaLiberada) decisao.eventosASeguir().get(0);
            assertThat(liberacao.reservaId()).isEqualTo(RESERVA);
        }

        @Test
        @DisplayName("so vira cancelado depois que a reserva volta")
        void sóFinalizaAposCompensar() {
            reservarEstoque();
            pedido.processar(PagamentoRecusado.de(PEDIDO, PAGAMENTO,
                    PagamentoRecusado.Motivo.CARTAO_INVALIDO, AGORA), AGORA);

            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CANCELANDO);

            var decisao = pedido.processar(
                    ReservaLiberada.de(PEDIDO, RESERVA, "compensacao", AGORA), AGORA);

            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CANCELADO);
            assertThat(decisao.eventosASeguir())
                    .singleElement()
                    .isInstanceOf(PedidoCancelado.class);
        }

        @Test
        @DisplayName("preserva o motivo original ate o fim da compensacao")
        void preservaOMotivo() {
            reservarEstoque();
            pedido.processar(PagamentoRecusado.de(PEDIDO, PAGAMENTO,
                    PagamentoRecusado.Motivo.SUSPEITA_DE_FRAUDE, AGORA), AGORA);
            var decisao = pedido.processar(
                    ReservaLiberada.de(PEDIDO, RESERVA, "compensacao", AGORA), AGORA);

            var cancelado = (PedidoCancelado) decisao.eventosASeguir().get(0);
            assertThat(cancelado.motivo()).isEqualTo(PedidoCancelado.Motivo.PAGAMENTO_RECUSADO);
            assertThat(cancelado.detalhe()).isEqualTo("SUSPEITA_DE_FRAUDE");
        }
    }

    @Nested
    @DisplayName("diante de evento repetido")
    class DianteDeEventoRepetido {

        @Test
        @DisplayName("segunda reserva do mesmo pedido nao muda nada")
        void reservaRepetida() {
            reservarEstoque();

            var repetido = pedido.processar(EstoqueReservado.de(PEDIDO, RESERVA, AGORA), AGORA);

            assertThat(repetido.mudouDeEstado()).isFalse();
            assertThat(repetido.produziuEventos()).isFalse();
            assertThat(pedido.status()).isEqualTo(StatusDoPedido.ESTOQUE_RESERVADO);
        }

        @Test
        @DisplayName("segundo pagamento aprovado nao confirma duas vezes")
        void pagamentoRepetido() {
            reservarEstoque();
            pedido.processar(PagamentoAprovado.de(PEDIDO, PAGAMENTO, "tx-1",
                    Dinheiro.de("56.00"), AGORA), AGORA);

            var repetido = pedido.processar(PagamentoAprovado.de(PEDIDO, PAGAMENTO, "tx-1",
                    Dinheiro.de("56.00"), AGORA), AGORA);

            assertThat(repetido.mudouDeEstado()).isFalse();
            assertThat(repetido.produziuEventos()).isFalse();
            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CONFIRMADO);
        }

        @Test
        @DisplayName("liberacao de reserva repetida nao cancela duas vezes")
        void liberacaoRepetida() {
            reservarEstoque();
            pedido.processar(PagamentoRecusado.de(PEDIDO, PAGAMENTO,
                    PagamentoRecusado.Motivo.SALDO_INSUFICIENTE, AGORA), AGORA);
            pedido.processar(ReservaLiberada.de(PEDIDO, RESERVA, "compensacao", AGORA), AGORA);

            var repetido = pedido.processar(
                    ReservaLiberada.de(PEDIDO, RESERVA, "compensacao", AGORA), AGORA);

            assertThat(repetido.produziuEventos()).isFalse();
        }
    }

    @Nested
    @DisplayName("diante de evento fora de ordem")
    class DianteDeEventoForaDeOrdem {

        @Test
        @DisplayName("pagamento aprovado antes da reserva e ignorado")
        void pagamentoAntesDaReserva() {
            var decisao = pedido.processar(PagamentoAprovado.de(PEDIDO, PAGAMENTO, "tx-1",
                    Dinheiro.de("56.00"), AGORA), AGORA);

            assertThat(decisao.mudouDeEstado()).isFalse();
            assertThat(pedido.status()).isEqualTo(StatusDoPedido.RECEBIDO);
            assertThat(decisao.observacao()).contains("RECEBIDO");
        }

        @Test
        @DisplayName("liberacao de reserva sem cancelamento em curso e ignorada")
        void liberacaoSemCancelamento() {
            reservarEstoque();

            var decisao = pedido.processar(
                    ReservaLiberada.de(PEDIDO, RESERVA, "solta", AGORA), AGORA);

            assertThat(decisao.mudouDeEstado()).isFalse();
            assertThat(pedido.status()).isEqualTo(StatusDoPedido.ESTOQUE_RESERVADO);
        }

        @Test
        @DisplayName("recusa de estoque depois da reserva e ignorada")
        void recusaDepoisDaReserva() {
            reservarEstoque();

            var decisao = pedido.processar(
                    EstoqueRecusado.de(PEDIDO, List.of("CAF-500"), "tarde", AGORA), AGORA);

            assertThat(decisao.mudouDeEstado()).isFalse();
            assertThat(pedido.status()).isEqualTo(StatusDoPedido.ESTOQUE_RESERVADO);
        }
    }

    @Nested
    @DisplayName("diante de evento atrasado, com o pedido ja encerrado")
    class DianteDeEventoAtrasado {

        @ParameterizedTest
        @EnumSource(value = PagamentoRecusado.Motivo.class)
        @DisplayName("recusa de pagamento apos confirmacao nao desfaz o pedido")
        void recusaAposConfirmacao(PagamentoRecusado.Motivo motivo) {
            reservarEstoque();
            pedido.processar(PagamentoAprovado.de(PEDIDO, PAGAMENTO, "tx-1",
                    Dinheiro.de("56.00"), AGORA), AGORA);

            var atrasado = pedido.processar(
                    PagamentoRecusado.de(PEDIDO, PAGAMENTO, motivo, AGORA), AGORA);

            assertThat(atrasado.mudouDeEstado()).isFalse();
            assertThat(atrasado.observacao()).contains("chegou tarde");
            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CONFIRMADO);
        }

        @Test
        @DisplayName("reserva que chega apos o cancelamento nao ressuscita o pedido")
        void reservaAposCancelamento() {
            pedido.processar(EstoqueRecusado.de(PEDIDO, List.of("CAF-500"), "zero", AGORA), AGORA);

            var atrasado = pedido.processar(EstoqueReservado.de(PEDIDO, RESERVA, AGORA), AGORA);

            assertThat(atrasado.mudouDeEstado()).isFalse();
            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CANCELADO);
        }
    }

    @Nested
    @DisplayName("na corrida entre aprovacao e cancelamento")
    class NaCorrida {

        /**
         * O caso mais desagradavel de todos, e o que justifica o estorno
         * existir: o cliente cancelou, a saga entrou em compensacao, e a
         * autorizacao do cartao chegou logo depois. O dinheiro ja saiu.
         */
        @Test
        @DisplayName("pagamento aprovado durante o cancelamento dispara estorno")
        void aprovacaoDuranteCancelamentoEstorna() {
            reservarEstoque();
            pedido.cancelarAPedidoDoCliente(AGORA);
            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CANCELANDO);

            var decisao = pedido.processar(PagamentoAprovado.de(PEDIDO, PAGAMENTO, "tx-1",
                    Dinheiro.de("56.00"), AGORA), AGORA);

            var estorno = (PagamentoEstornado) decisao.eventosASeguir().get(0);
            assertThat(estorno.valor()).isEqualTo(Dinheiro.de("56.00"));
            assertThat(estorno.pagamentoId()).isEqualTo(PAGAMENTO);
            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CANCELANDO);
        }

        @Test
        @DisplayName("estorno confirmado ainda espera a reserva voltar")
        void estornoNaoFinalizaSozinho() {
            reservarEstoque();
            pedido.cancelarAPedidoDoCliente(AGORA);
            pedido.processar(PagamentoAprovado.de(PEDIDO, PAGAMENTO, "tx-1",
                    Dinheiro.de("56.00"), AGORA), AGORA);

            var decisao = pedido.processar(PagamentoEstornado.de(PEDIDO, PAGAMENTO,
                    Dinheiro.de("56.00"), "cancelamento", AGORA), AGORA);

            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CANCELANDO);
            assertThat(decisao.eventosASeguir())
                    .singleElement()
                    .isInstanceOf(ReservaLiberada.class);
        }
    }

    @Nested
    @DisplayName("no cancelamento pelo cliente")
    class NoCancelamentoPeloCliente {

        @Test
        @DisplayName("antes da reserva, cancela na hora")
        void antesDaReserva() {
            var decisao = pedido.cancelarAPedidoDoCliente(AGORA);

            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CANCELADO);
            var cancelado = (PedidoCancelado) decisao.eventosASeguir().get(0);
            assertThat(cancelado.motivo()).isEqualTo(PedidoCancelado.Motivo.PEDIDO_DO_CLIENTE);
        }

        @Test
        @DisplayName("depois da reserva, compensa antes de encerrar")
        void depoisDaReserva() {
            reservarEstoque();

            var decisao = pedido.cancelarAPedidoDoCliente(AGORA);

            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CANCELANDO);
            assertThat(decisao.eventosASeguir())
                    .singleElement()
                    .isInstanceOf(ReservaLiberada.class);
        }

        @Test
        @DisplayName("pedido ja confirmado nao aceita cancelamento por este caminho")
        void confirmadoNaoCancela() {
            reservarEstoque();
            pedido.processar(PagamentoAprovado.de(PEDIDO, PAGAMENTO, "tx-1",
                    Dinheiro.de("56.00"), AGORA), AGORA);

            var decisao = pedido.cancelarAPedidoDoCliente(AGORA);

            assertThat(decisao.mudouDeEstado()).isFalse();
            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CONFIRMADO);
        }
    }

    @Nested
    @DisplayName("no prazo da saga")
    class NoPrazoDaSaga {

        @Test
        @DisplayName("dentro do prazo, nao mexe")
        void dentroDoPrazo() {
            var decisao = pedido.verificarPrazo(AGORA.plus(Duration.ofMinutes(14)));

            assertThat(decisao.mudouDeEstado()).isFalse();
            assertThat(pedido.status()).isEqualTo(StatusDoPedido.RECEBIDO);
        }

        @Test
        @DisplayName("estourado antes da reserva, cancela sem compensar")
        void estouradoAntesDaReserva() {
            var decisao = pedido.verificarPrazo(AGORA.plus(Duration.ofMinutes(16)));

            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CANCELADO);
            assertThat(pedido.motivoDoCancelamento())
                    .isEqualTo(PedidoCancelado.Motivo.TEMPO_ESGOTADO);
            assertThat(decisao.eventosASeguir())
                    .singleElement()
                    .isInstanceOf(PedidoCancelado.class);
        }

        @Test
        @DisplayName("estourado apos a reserva, compensa antes de encerrar")
        void estouradoAposAReserva() {
            reservarEstoque();

            var decisao = pedido.verificarPrazo(AGORA.plus(Duration.ofMinutes(16)));

            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CANCELANDO);
            assertThat(decisao.eventosASeguir())
                    .singleElement()
                    .isInstanceOf(ReservaLiberada.class);
        }

        /**
         * O relogio da saga anda a cada avanco. Sem isso, um pedido que
         * respondeu no minuto 14 seria cancelado no minuto 16 mesmo estando
         * saudavel.
         */
        @Test
        @DisplayName("cada avanco reinicia a contagem do prazo")
        void avancoReiniciaOPrazo() {
            var quaseNoLimite = AGORA.plus(Duration.ofMinutes(14));
            pedido.processar(EstoqueReservado.de(PEDIDO, RESERVA, quaseNoLimite), quaseNoLimite);

            var decisao = pedido.verificarPrazo(AGORA.plus(Duration.ofMinutes(16)));

            assertThat(decisao.mudouDeEstado()).isFalse();
            assertThat(pedido.status()).isEqualTo(StatusDoPedido.ESTOQUE_RESERVADO);
        }

        @Test
        @DisplayName("pedido ja encerrado nao e afetado pelo prazo")
        void encerradoNaoEAfetado() {
            pedido.processar(EstoqueRecusado.de(PEDIDO, List.of("A"), "zero", AGORA), AGORA);

            var decisao = pedido.verificarPrazo(AGORA.plus(Duration.ofDays(30)));

            assertThat(decisao.mudouDeEstado()).isFalse();
            assertThat(pedido.status()).isEqualTo(StatusDoPedido.CANCELADO);
        }
    }

    @Nested
    @DisplayName("na tabela de transicoes")
    class NaTabelaDeTransicoes {

        @Test
        @DisplayName("estados finais nao tem saida")
        void finaisNaoTemSaida() {
            assertThat(StatusDoPedido.CONFIRMADO.terminal()).isTrue();
            assertThat(StatusDoPedido.CANCELADO.terminal()).isTrue();
            assertThat(StatusDoPedido.RECEBIDO.terminal()).isFalse();
            assertThat(StatusDoPedido.ESTOQUE_RESERVADO.terminal()).isFalse();
            assertThat(StatusDoPedido.CANCELANDO.terminal()).isFalse();
        }

        @Test
        @DisplayName("nao existe caminho de volta de confirmado para nada")
        void confirmadoNaoVolta() {
            for (StatusDoPedido destino : StatusDoPedido.values()) {
                assertThat(StatusDoPedido.CONFIRMADO.podeIrPara(destino))
                        .describedAs("CONFIRMADO nao pode ir para %s", destino)
                        .isFalse();
            }
        }

        @Test
        @DisplayName("cancelando so pode terminar em cancelado")
        void cancelandoSoTerminaEmCancelado() {
            assertThat(StatusDoPedido.CANCELANDO.podeIrPara(StatusDoPedido.CANCELADO)).isTrue();
            assertThat(StatusDoPedido.CANCELANDO.podeIrPara(StatusDoPedido.CONFIRMADO)).isFalse();
            assertThat(StatusDoPedido.CANCELANDO.podeIrPara(StatusDoPedido.RECEBIDO)).isFalse();
        }

        @Test
        @DisplayName("recebido nao pode saltar direto para confirmado")
        void recebidoNaoSalta() {
            assertThat(StatusDoPedido.RECEBIDO.podeIrPara(StatusDoPedido.CONFIRMADO)).isFalse();
            assertThat(StatusDoPedido.RECEBIDO.podeIrPara(StatusDoPedido.ESTOQUE_RESERVADO)).isTrue();
        }
    }
}
