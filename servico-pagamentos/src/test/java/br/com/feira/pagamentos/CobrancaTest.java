package br.com.feira.pagamentos;

import br.com.feira.contratos.Dinheiro;
import br.com.feira.contratos.PagamentoRecusado;
import br.com.feira.pagamentos.dominio.Adquirente;
import br.com.feira.pagamentos.dominio.Cobranca;
import br.com.feira.pagamentos.infraestrutura.AdquirenteSimulado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Cobranca e adquirente")
class CobrancaTest {

    private static final Instant AGORA = Instant.parse("2026-08-26T12:00:00Z");
    private static final UUID PEDIDO = UUID.randomUUID();
    private static final Dinheiro VALOR = Dinheiro.de("56.00");

    @Nested
    @DisplayName("no ciclo de vida da cobranca")
    class NoCicloDeVida {

        @Test
        @DisplayName("nasce pendente com o valor do pedido")
        void nascePendente() {
            var cobranca = Cobranca.pendente(PEDIDO, VALOR, AGORA);

            assertThat(cobranca.situacao()).isEqualTo(Cobranca.Situacao.PENDENTE);
            assertThat(cobranca.pendente()).isTrue();
            assertThat(cobranca.valor()).isEqualTo(VALOR);
            assertThat(cobranca.encerradaEm()).isNull();
            assertThat(cobranca.pedidoId()).isEqualTo(PEDIDO);
        }

        @Test
        @DisplayName("aprovar guarda a referencia da transacao")
        void aprovaGuardandoReferencia() {
            var cobranca = Cobranca.pendente(PEDIDO, VALOR, AGORA);

            assertThat(cobranca.aprovar("tx-123", AGORA)).isTrue();

            assertThat(cobranca.situacao()).isEqualTo(Cobranca.Situacao.APROVADA);
            assertThat(cobranca.referenciaExterna())
                    .describedAs("sem a referencia nao ha conciliacao possivel")
                    .isEqualTo("tx-123");
        }

        @Test
        @DisplayName("recusar guarda o motivo e encerra")
        void recusaGuardandoMotivo() {
            var cobranca = Cobranca.pendente(PEDIDO, VALOR, AGORA);

            assertThat(cobranca.recusar(PagamentoRecusado.Motivo.SALDO_INSUFICIENTE, "tx-9", AGORA))
                    .isTrue();

            assertThat(cobranca.situacao()).isEqualTo(Cobranca.Situacao.RECUSADA);
            assertThat(cobranca.motivoDaRecusa())
                    .isEqualTo(PagamentoRecusado.Motivo.SALDO_INSUFICIENTE);
            assertThat(cobranca.encerradaEm()).isEqualTo(AGORA);
        }

        /**
         * O caso que a reentrega do Kafka garante que vai acontecer. Aprovar
         * duas vezes precisa cobrar uma vez so.
         */
        @Test
        @DisplayName("aprovar duas vezes so tem efeito uma vez")
        void aprovarDuasVezes() {
            var cobranca = Cobranca.pendente(PEDIDO, VALOR, AGORA);
            cobranca.aprovar("tx-1", AGORA);

            assertThat(cobranca.aprovar("tx-2", AGORA)).isFalse();
            assertThat(cobranca.referenciaExterna())
                    .describedAs("a segunda tentativa nao pode sobrescrever a transacao real")
                    .isEqualTo("tx-1");
        }

        @Test
        @DisplayName("cobranca recusada nao vira aprovada")
        void recusadaNaoAprova() {
            var cobranca = Cobranca.pendente(PEDIDO, VALOR, AGORA);
            cobranca.recusar(PagamentoRecusado.Motivo.CARTAO_INVALIDO, "tx-1", AGORA);

            assertThat(cobranca.aprovar("tx-2", AGORA)).isFalse();
            assertThat(cobranca.situacao()).isEqualTo(Cobranca.Situacao.RECUSADA);
        }

        @Test
        @DisplayName("estorna so o que foi aprovado")
        void estornaSoAprovada() {
            var aprovada = Cobranca.pendente(PEDIDO, VALOR, AGORA);
            aprovada.aprovar("tx-1", AGORA);
            var recusada = Cobranca.pendente(UUID.randomUUID(), VALOR, AGORA);
            recusada.recusar(PagamentoRecusado.Motivo.CARTAO_INVALIDO, "tx-2", AGORA);
            var pendente = Cobranca.pendente(UUID.randomUUID(), VALOR, AGORA);

            assertThat(aprovada.estornar(AGORA)).isTrue();
            assertThat(recusada.estornar(AGORA)).isFalse();
            assertThat(pendente.estornar(AGORA)).isFalse();
        }

        @Test
        @DisplayName("estornar duas vezes nao devolve o dinheiro duas vezes")
        void estornarDuasVezes() {
            var cobranca = Cobranca.pendente(PEDIDO, VALOR, AGORA);
            cobranca.aprovar("tx-1", AGORA);

            assertThat(cobranca.estornar(AGORA)).isTrue();
            assertThat(cobranca.estornar(AGORA)).isFalse();
            assertThat(cobranca.situacao()).isEqualTo(Cobranca.Situacao.ESTORNADA);
        }

        @Test
        @DisplayName("cobranca estornada nao volta a ser aprovada")
        void estornadaNaoVolta() {
            var cobranca = Cobranca.pendente(PEDIDO, VALOR, AGORA);
            cobranca.aprovar("tx-1", AGORA);
            cobranca.estornar(AGORA);

            assertThat(cobranca.aprovar("tx-2", AGORA)).isFalse();
            assertThat(cobranca.situacao()).isEqualTo(Cobranca.Situacao.ESTORNADA);
        }
    }

    @Nested
    @DisplayName("no adquirente simulado")
    class NoAdquirente {

        /**
         * Valores com desfecho fixo, como os cartoes de teste que qualquer
         * adquirente de verdade oferece em homologacao. Sem eles, demonstrar o
         * cancelamento dependeria de sorte.
         */
        @ParameterizedTest
        @CsvSource({
                "10.13, SALDO_INSUFICIENTE",
                "99.66, SUSPEITA_DE_FRAUDE",
                "24.99, FALHA_TEMPORARIA"
        })
        @DisplayName("valores reservados forcam um motivo de recusa")
        void valoresForcamRecusa(String valor, PagamentoRecusado.Motivo esperado) {
            var adquirente = new AdquirenteSimulado(0);

            var resposta = adquirente.autorizar(PEDIDO, Dinheiro.de(valor));

            assertThat(resposta.autorizada()).isFalse();
            assertThat(resposta.motivo()).isEqualTo(esperado);
            assertThat(resposta.referenciaExterna())
                    .describedAs("recusa tambem gera referencia, para conciliar")
                    .isNotBlank();
        }

        @Test
        @DisplayName("com recusa zerada, valor comum e sempre aprovado")
        void semRecusaAprovaSempre() {
            var adquirente = new AdquirenteSimulado(0);

            for (int i = 0; i < 50; i++) {
                var resposta = adquirente.autorizar(PEDIDO, Dinheiro.de("50.00"));
                assertThat(resposta.autorizada()).isTrue();
                assertThat(resposta.motivo()).isNull();
            }
        }

        @Test
        @DisplayName("com recusa em cem por cento, valor comum e sempre recusado")
        void recusaTotal() {
            var adquirente = new AdquirenteSimulado(100);

            for (int i = 0; i < 20; i++) {
                var resposta = adquirente.autorizar(PEDIDO, Dinheiro.de("50.00"));
                assertThat(resposta.autorizada()).isFalse();
                assertThat(resposta.motivo()).isNotNull();
            }
        }

        @Test
        @DisplayName("cada autorizacao gera uma referencia distinta")
        void referenciaUnica() {
            var adquirente = new AdquirenteSimulado(0);

            var primeira = adquirente.autorizar(PEDIDO, Dinheiro.de("50.00"));
            var segunda = adquirente.autorizar(PEDIDO, Dinheiro.de("50.00"));

            assertThat(primeira.referenciaExterna()).isNotEqualTo(segunda.referenciaExterna());
        }

        @Test
        @DisplayName("so falha temporaria permite nova tentativa")
        void apenasTemporariaPermiteRetentar() {
            var adquirente = new AdquirenteSimulado(0);

            var temporaria = adquirente.autorizar(PEDIDO, Dinheiro.de("10.99"));
            var definitiva = adquirente.autorizar(PEDIDO, Dinheiro.de("10.13"));

            assertThat(temporaria.motivo().permiteNovaTentativa()).isTrue();
            assertThat(definitiva.motivo().permiteNovaTentativa()).isFalse();
        }

        @Test
        @DisplayName("a resposta aprovada nunca carrega motivo")
        void aprovadaSemMotivo() {
            var resposta = Adquirente.Resposta.aprovada("tx-1");

            assertThat(resposta.autorizada()).isTrue();
            assertThat(resposta.motivo()).isNull();
        }
    }
}
