package br.com.feira.plataforma.observabilidade;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Leva o rastro de um lado para o outro do outbox e do broker.
 *
 * <p>O problema que esta classe existe para resolver: o evento é gravado no
 * outbox dentro da transação de quem atendeu a requisição, e publicado depois,
 * por outra thread, quando aquela requisição já acabou. O contexto de rastro
 * vive na thread. No commit ele morre.
 *
 * <p>Sem isto, o painel mostra dois rastros desligados: um que termina na
 * gravação e outro que começa do nada na publicação. O caso que interessa
 * investigar, que é o pedido do usuário atravessando quatro serviços, é
 * justamente o que se perde.
 *
 * <p>A serialização usa o formato do W3C, o mesmo que viaja em cabeçalho HTTP.
 * Guardar o padrão em vez de uma invenção nossa é o que deixa o rastro
 * atravessar processo, broker e serviço de terceiro sem tradução no meio.
 */
@Component
public class RastroDaMensagem {

    /** Nome do campo no W3C. Vale para cabeçalho HTTP, do Kafka e atributo do SNS. */
    public static final String CAMPO = "traceparent";

    private final Tracer tracer;
    private final Propagator propagador;

    public RastroDaMensagem(Tracer tracer, Propagator propagador) {
        this.tracer = tracer;
        this.propagador = propagador;
    }

    /**
     * O contexto atual em texto, ou nulo quando não há rastro em andamento.
     *
     * <p>Nulo é caso normal e não erro: evento que nasce de tarefa agendada não
     * tem requisição anterior para herdar.
     */
    public String capturar() {
        Span atual = tracer.currentSpan();
        if (atual == null) {
            return null;
        }
        Map<String, String> campos = new HashMap<>();
        propagador.inject(atual.context(), campos, Map::put);
        return campos.get(CAMPO);
    }

    /**
     * Abre um trecho filho do rastro que veio junto com a mensagem.
     *
     * <p>Filho, e não continuação do mesmo trecho, porque publicar e consumir
     * são trabalhos separados que acontecem em momentos diferentes: o painel
     * precisa mostrar os dois com duração própria, pendurados no mesmo pedido
     * de origem.
     *
     * <p>Quem chama fecha com {@code span.end()} num finally. Sem isso o trecho
     * fica aberto para sempre e o rastro nunca aparece completo.
     */
    public Span retomar(String traceparent, String nome) {
        Span.Builder construtor = traceparent == null || traceparent.isBlank()
                ? tracer.spanBuilder()
                : propagador.extract(Map.of(CAMPO, traceparent), Map::get);
        return construtor.name(nome).start();
    }

    /**
     * Roda o consumo de uma mensagem dentro de um trecho ligado a quem
     * publicou.
     *
     * <p>Existe para os dois ouvintes, do Kafka e do SQS, não repetirem o
     * mesmo abre, marca, fecha e trata erro. O transporte muda de onde o
     * {@code traceparent} é lido; daqui para dentro é igual.
     *
     * <p>A exceção sobe depois de marcada no trecho, porque quem decide o que
     * fazer com falha de consumo é o ouvinte: no Kafka, o tratador de erro com
     * fila morta; no SQS, deixar a mensagem voltar sem apagar.
     */
    public void consumindo(String traceparent, String topico, Runnable trabalho) {
        Span trecho = retomar(traceparent, "mensageria consumir");
        trecho.tag("mensageria.topico", topico);
        try (var escopo = tracer.withSpan(trecho)) {
            trabalho.run();
        } catch (RuntimeException erro) {
            trecho.error(erro);
            throw erro;
        } finally {
            trecho.end();
        }
    }
}
