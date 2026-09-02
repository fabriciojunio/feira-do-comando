package br.com.feira.plataforma.observabilidade;

import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Um {@link RastroDaMensagem} de verdade, com o OpenTelemetry real e nenhum
 * coletor por trás.
 *
 * <p>Dublê não serve aqui por dois motivos. O primeiro é que {@code consumindo}
 * recebe o trabalho e é ele quem executa: um dublê devolveria nulo e engoliria
 * a chamada, e o teste passaria sem nunca ter despachado a mensagem. O segundo
 * é que o que interessa provar é a propagação no formato do W3C, e o formato só
 * existe no propagador de verdade.
 *
 * <p>Os trechos ficam numa lista em memória, então o teste confere o que foi
 * aberto e como se ligou sem subir coletor nenhum.
 */
public final class RastroDeTeste {

    private RastroDeTeste() {
    }

    /** Guarda os trechos exportados em memória, para o teste conferir. */
    public static final class Memoria implements SpanExporter {

        private final List<SpanData> trechos = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> lote) {
            trechos.addAll(lote);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        public List<SpanData> trechos() {
            return trechos;
        }

        public SpanData ultimo() {
            return trechos.get(trechos.size() - 1);
        }
    }

    /** O rastro, o tracer por baixo dele e a memória onde os trechos caem. */
    public record Montagem(RastroDaMensagem rastro, io.micrometer.tracing.Tracer tracer, Memoria memoria) {
    }

    public static Montagem montar() {
        Memoria memoria = new Memoria();

        SdkTracerProvider provedor = SdkTracerProvider.builder()
                // Processador simples, e não em lote: em teste o trecho precisa
                // estar na memória assim que fecha, senão a asserção corre
                // antes do envio e o teste fica intermitente.
                .addSpanProcessor(SimpleSpanProcessor.create(memoria))
                .build();

        OpenTelemetry otel = OpenTelemetrySdk.builder()
                .setTracerProvider(provedor)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();

        var contextoAtual = new OtelCurrentTraceContext();
        var tracer = new OtelTracer(otel.getTracer("teste"), contextoAtual, evento -> {
        });
        Propagator propagador = new OtelPropagator(otel.getPropagators(), otel.getTracer("teste"));

        return new Montagem(new RastroDaMensagem(tracer, propagador), tracer, memoria);
    }

    /** Atalho para quem só precisa de um rastro que funcione. */
    public static RastroDaMensagem semColetor() {
        return montar().rastro();
    }
}
