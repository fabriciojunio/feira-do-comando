package br.com.feira.plataforma.outbox;

import br.com.feira.plataforma.observabilidade.RastroDaMensagem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PublicadorDoOutboxTest {
    @Test
    void metricaAcompanhaCrescimentoERecuperacaoDaFila() {
        var repositorio = mock(OutboxRepository.class);
        when(repositorio.countByPublicadaEmIsNull()).thenReturn(4L, 25L, 0L);
        var metricas = new SimpleMeterRegistry();
        var publicador = new PublicadorDoOutbox(repositorio, mock(KafkaTemplate.class), metricas,
                Clock.systemUTC(), mock(RastroDaMensagem.class), mock(Tracer.class));
        for (double esperado : new double[]{4, 25, 0}) {
            publicador.medirPendentes();
            assertThat(metricas.get("feira.outbox.pendentes").gauge().value()).isEqualTo(esperado);
        }
    }
}
