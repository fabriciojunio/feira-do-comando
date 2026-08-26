package br.com.feira.estoque.dominio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Item de estoque")
class ItemDeEstoqueTest {

    private static final Instant AGORA = Instant.parse("2026-08-26T10:00:00Z");

    private ItemDeEstoque comSaldo(int quantidade) {
        return new ItemDeEstoque("CAF-500", "Cafe torrado 500g", quantidade, AGORA);
    }

    @Nested
    @DisplayName("na criacao")
    class NaCriacao {

        @Test
        @DisplayName("comeca com tudo disponivel e nada reservado")
        void comecaLimpo() {
            var item = comSaldo(10);

            assertThat(item.disponivel()).isEqualTo(10);
            assertThat(item.reservado()).isZero();
            assertThat(item.vendido()).isZero();
            assertThat(item.emMaos()).isEqualTo(10);
        }

        @Test
        @DisplayName("recusa sku vazio e saldo negativo")
        void recusaInvalidos() {
            assertThatThrownBy(() -> new ItemDeEstoque(" ", "x", 1, AGORA))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new ItemDeEstoque("A", "x", -1, AGORA))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("ao reservar")
    class AoReservar {

        @Test
        @DisplayName("move do disponivel para o reservado sem mudar o que existe no galpao")
        void moveSemMudarOTotal() {
            var item = comSaldo(10);

            assertThat(item.reservar(3, AGORA)).isTrue();

            assertThat(item.disponivel()).isEqualTo(7);
            assertThat(item.reservado()).isEqualTo(3);
            assertThat(item.emMaos())
                    .describedAs("reserva nao tira mercadoria do galpao")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("aceita reservar exatamente o que resta")
        void aceitaOSaldoExato() {
            var item = comSaldo(3);

            assertThat(item.reservar(3, AGORA)).isTrue();
            assertThat(item.disponivel()).isZero();
        }

        @Test
        @DisplayName("recusa mais do que existe, sem mexer em nada")
        void recusaSemEfeitoColateral() {
            var item = comSaldo(2);

            assertThat(item.reservar(3, AGORA)).isFalse();

            assertThat(item.disponivel()).isEqualTo(2);
            assertThat(item.reservado()).isZero();
        }

        @Test
        @DisplayName("recusa quantidade zero ou negativa")
        void recusaQuantidadeInvalida() {
            var item = comSaldo(5);

            assertThatThrownBy(() -> item.reservar(0, AGORA))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> item.reservar(-1, AGORA))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("duas reservas seguidas nao passam do saldo")
        void duasReservasRespeitamOSaldo() {
            var item = comSaldo(5);

            assertThat(item.reservar(3, AGORA)).isTrue();
            assertThat(item.reservar(3, AGORA)).isFalse();
            assertThat(item.reservar(2, AGORA)).isTrue();
            assertThat(item.disponivel()).isZero();
            assertThat(item.reservado()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("ao liberar")
    class AoLiberar {

        @Test
        @DisplayName("devolve ao disponivel")
        void devolve() {
            var item = comSaldo(10);
            item.reservar(4, AGORA);

            item.liberar(4, AGORA);

            assertThat(item.disponivel()).isEqualTo(10);
            assertThat(item.reservado()).isZero();
        }

        /**
         * O caso que justifica o {@code Math.min}: a saga pode mandar liberar
         * duas vezes. Sem o limite, a segunda chamada criaria mercadoria que
         * nunca existiu, e o galpao passaria a "ter" mais do que tem.
         */
        @Test
        @DisplayName("liberar duas vezes nao inventa mercadoria")
        void liberarDuasVezesNaoInventa() {
            var item = comSaldo(10);
            item.reservar(4, AGORA);

            item.liberar(4, AGORA);
            item.liberar(4, AGORA);

            assertThat(item.disponivel()).isEqualTo(10);
            assertThat(item.reservado()).isZero();
            assertThat(item.emMaos()).isEqualTo(10);
        }

        @Test
        @DisplayName("liberar mais do que estava reservado devolve so o que havia")
        void liberarAlemDoReservado() {
            var item = comSaldo(10);
            item.reservar(2, AGORA);

            item.liberar(5, AGORA);

            assertThat(item.disponivel()).isEqualTo(10);
            assertThat(item.reservado()).isZero();
        }
    }

    @Nested
    @DisplayName("ao baixar")
    class AoBaixar {

        @Test
        @DisplayName("tira do reservado e soma no vendido")
        void baixa() {
            var item = comSaldo(10);
            item.reservar(3, AGORA);

            item.baixar(3, AGORA);

            assertThat(item.reservado()).isZero();
            assertThat(item.vendido()).isEqualTo(3);
            assertThat(item.disponivel()).isEqualTo(7);
            assertThat(item.emMaos())
                    .describedAs("mercadoria vendida saiu do galpao")
                    .isEqualTo(7);
        }

        @Test
        @DisplayName("baixar duas vezes nao vende duas vezes")
        void baixarDuasVezes() {
            var item = comSaldo(10);
            item.reservar(3, AGORA);

            item.baixar(3, AGORA);
            item.baixar(3, AGORA);

            assertThat(item.vendido()).isEqualTo(3);
            assertThat(item.emMaos()).isEqualTo(7);
        }

        @Test
        @DisplayName("baixar sem reserva nao faz nada")
        void baixarSemReserva() {
            var item = comSaldo(10);

            item.baixar(3, AGORA);

            assertThat(item.vendido()).isZero();
            assertThat(item.disponivel()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("na reposicao")
    class NaReposicao {

        @Test
        @DisplayName("soma ao disponivel")
        void repoe() {
            var item = comSaldo(2);

            item.repor(8, AGORA);

            assertThat(item.disponivel()).isEqualTo(10);
        }

        @Test
        @DisplayName("nao mexe no que esta reservado")
        void naoMexeNoReservado() {
            var item = comSaldo(5);
            item.reservar(5, AGORA);

            item.repor(10, AGORA);

            assertThat(item.disponivel()).isEqualTo(10);
            assertThat(item.reservado()).isEqualTo(5);
            assertThat(item.emMaos()).isEqualTo(15);
        }
    }

    @Nested
    @DisplayName("no ciclo completo")
    class NoCicloCompleto {

        @Test
        @DisplayName("venda: reserva, baixa, e o saldo fecha")
        void venda() {
            var item = comSaldo(10);

            item.reservar(4, AGORA);
            item.baixar(4, AGORA);

            assertThat(item.disponivel()).isEqualTo(6);
            assertThat(item.reservado()).isZero();
            assertThat(item.vendido()).isEqualTo(4);
        }

        @Test
        @DisplayName("desistencia: reserva, libera, e nada mudou")
        void desistencia() {
            var item = comSaldo(10);

            item.reservar(4, AGORA);
            item.liberar(4, AGORA);

            assertThat(item.disponivel()).isEqualTo(10);
            assertThat(item.reservado()).isZero();
            assertThat(item.vendido()).isZero();
        }

        @Test
        @DisplayName("a soma de disponivel, reservado e vendido nunca cresce sozinha")
        void somaNaoCresce() {
            var item = comSaldo(10);
            int totalInicial = item.disponivel() + item.reservado() + item.vendido();

            item.reservar(3, AGORA);
            item.liberar(1, AGORA);
            item.baixar(2, AGORA);
            item.liberar(5, AGORA);

            assertThat(item.disponivel() + item.reservado() + item.vendido())
                    .isEqualTo(totalInicial);
        }
    }
}
