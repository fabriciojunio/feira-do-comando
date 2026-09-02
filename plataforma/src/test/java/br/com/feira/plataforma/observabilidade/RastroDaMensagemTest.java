package br.com.feira.plataforma.observabilidade;

import io.micrometer.tracing.Span;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * O rastro atravessando a fronteira onde ele normalmente se perde.
 *
 * <p>A fronteira é o outbox. O evento é gravado na transação de quem atendeu a
 * requisição e publicado depois, por outra thread, quando aquela requisição já
 * terminou. O contexto de rastro vive na thread, então no commit ele morre, e o
 * painel mostra dois rastros desligados em vez de um pedido inteiro.
 *
 * <p>O teste usa o OpenTelemetry de verdade, e não um dublê, porque o que
 * precisa ser provado é o formato do W3C: um dublê aceitaria qualquer texto
 * como contexto e o teste passaria com uma propagação que não funciona.
 */
@DisplayName("Rastro da mensagem")
class RastroDaMensagemTest {

    private final RastroDeTeste.Montagem montagem = RastroDeTeste.montar();
    private final RastroDaMensagem rastro = montagem.rastro();
    private final RastroDeTeste.Memoria memoria = montagem.memoria();

    @Test
    @DisplayName("sem rastro em andamento não inventa contexto: devolve nulo")
    void semRastroDevolveNulo() {
        assertThat(rastro.capturar()).isNull();
    }

    @Test
    @DisplayName("captura em formato do W3C, que é o que atravessa HTTP, Kafka e SNS sem tradução")
    void capturaNoFormatoDoPadrao() {
        Span trecho = rastro.retomar(null, "requisicao");
        String capturado = comTrechoAberto(trecho, rastro::capturar);

        // 00-<32 hex do rastro>-<16 hex do trecho>-<2 hex de flags>
        assertThat(capturado).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    }

    @Test
    @DisplayName("o que foi gravado no outbox reabre o MESMO rastro na publicação, e não um novo")
    void publicacaoContinuaORastroDaRequisicao() {
        Span daRequisicao = rastro.retomar(null, "requisicao");
        String gravadoNoOutbox = comTrechoAberto(daRequisicao, rastro::capturar);
        String rastroOriginal = daRequisicao.context().traceId();
        daRequisicao.end();

        // Aqui a requisição já acabou. É a thread do publicador que segue.
        Span daPublicacao = rastro.retomar(gravadoNoOutbox, "outbox publicar");
        daPublicacao.end();

        assertThat(daPublicacao.context().traceId())
                .as("publicação tem que cair no rastro da requisição que gerou o evento")
                .isEqualTo(rastroOriginal);
    }

    @Test
    @DisplayName("o consumo do outro serviço cai no mesmo rastro do publicador")
    void consumoContinuaORastroDoPublicador() {
        Span daPublicacao = rastro.retomar(null, "outbox publicar");
        String noCabecalho = comTrechoAberto(daPublicacao, rastro::capturar);
        String rastroOriginal = daPublicacao.context().traceId();
        daPublicacao.end();

        AtomicReference<String> noConsumo = new AtomicReference<>();
        rastro.consumindo(noCabecalho, "feira.estoque",
                () -> noConsumo.set(rastro.capturar()));

        assertThat(noConsumo.get()).contains(rastroOriginal);
    }

    @Test
    @DisplayName("evento de tarefa agendada, sem requisição de origem, não quebra ao gravar")
    void tarefaAgendadaNaoQuebra() {
        // É o caso que faz a coluna do outbox aceitar nulo. Se capturar()
        // lançasse aqui, toda tarefa agendada quebraria na gravação.
        assertThat(rastro.capturar()).isNull();
    }

    @Test
    @DisplayName("consumir sem contexto anterior abre rastro próprio em vez de ficar sem nenhum")
    void consumoSemContextoAbreORastro() {
        AtomicBoolean rodou = new AtomicBoolean(false);

        rastro.consumindo(null, "feira.estoque", () -> rodou.set(true));

        assertThat(rodou).isTrue();
        assertThat(memoria.trechos()).isNotEmpty();
    }

    @Test
    @DisplayName("consumir executa o trabalho, e não só mede o tempo em volta dele")
    void consumoExecutaOTrabalho() {
        AtomicBoolean despachou = new AtomicBoolean(false);

        rastro.consumindo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                "feira.pedidos", () -> despachou.set(true));

        assertThat(despachou).isTrue();
    }

    @Test
    @DisplayName("falha no consumo sobe, porque quem decide o destino da mensagem é o ouvinte")
    void falhaNoConsumoSobe() {
        assertThatThrownBy(() -> rastro.consumindo(null, "feira.estoque", () -> {
            throw new IllegalStateException("banco fora");
        })).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("o trecho fecha mesmo quando o consumo falha, senão o rastro fica aberto para sempre")
    void trechoFechaNaFalha() {
        try {
            rastro.consumindo(null, "feira.estoque", () -> {
                throw new IllegalStateException("falhou");
            });
        } catch (IllegalStateException esperada) {
            // o que interessa é o trecho ter sido exportado, não a exceção
        }

        assertThat(memoria.trechos())
                .as("trecho não exportado é trecho que ficou aberto")
                .isNotEmpty();
    }

    @Test
    @DisplayName("a falha fica marcada no trecho, para o painel mostrar o erro e não só a lentidão")
    void falhaFicaMarcadaNoTrecho() {
        try {
            rastro.consumindo(null, "feira.estoque", () -> {
                throw new IllegalStateException("banco fora");
            });
        } catch (IllegalStateException esperada) {
            // idem
        }

        assertThat(memoria.ultimo().getStatus().getStatusCode())
                .isEqualTo(io.opentelemetry.api.trace.StatusCode.ERROR);
    }

    @Test
    @DisplayName("o trecho leva o tópico, que é o primeiro filtro de quem abre o painel")
    void trechoMarcaOTopico() {
        rastro.consumindo(null, "feira.pagamentos", () -> {
        });

        assertThat(memoria.ultimo().getAttributes().asMap().toString())
                .contains("feira.pagamentos");
    }

    @Test
    @DisplayName("retomar com texto vazio se comporta como sem rastro, e não estoura")
    void retomarVazio() {
        Span trecho = rastro.retomar("", "outbox publicar");

        assertThat(trecho).isNotNull();
        trecho.end();
    }

    @Test
    @DisplayName("contexto malformado não derruba o consumo: abre rastro novo e segue")
    void contextoMalformadoNaoDerruba() {
        AtomicBoolean rodou = new AtomicBoolean(false);

        rastro.consumindo("isto-nao-e-um-traceparent", "feira.estoque", () -> rodou.set(true));

        assertThat(rodou)
                .as("mensagem com cabeçalho estragado ainda precisa ser processada")
                .isTrue();
    }

    @Test
    @DisplayName("o nome do campo é o do W3C, e não uma invenção nossa")
    void campoEhOPadrao() {
        assertThat(RastroDaMensagem.CAMPO).isEqualTo("traceparent");
    }

    private <T> T comTrechoAberto(Span trecho, java.util.function.Supplier<T> acao) {
        var tracer = new io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext();
        try (var ignorado = tracer.maybeScope(trecho.context())) {
            return acao.get();
        }
    }
}
