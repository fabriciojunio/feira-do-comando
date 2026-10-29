package br.com.feira.pedidos.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Tratador de erros")
class TratadorDeErrosTest {

    @Test
    @DisplayName("quebra de linha no caminho não cria linha falsa no log")
    void logSemQuebraDeLinha() {
        var logger = (Logger) LoggerFactory.getLogger(TratadorDeErros.class);
        var registros = new ListAppender<ILoggingEvent>();
        registros.start();
        logger.addAppender(registros);
        try {
            var requisicao = new MockHttpServletRequest("GET", "/pedidos/x\nINFO pagamento aprovado");

            var resposta = new TratadorDeErros(java.time.Clock.systemUTC())
                    .inesperado(new IllegalStateException("falha"), requisicao);

            // O caminho não é URI válida: a resposta sai no formato de
            // problema, só sem o campo instance, em vez de estourar.
            assertThat(resposta.getStatus()).isEqualTo(500);
            assertThat(resposta.getInstance()).isNull();

            assertThat(registros.list).hasSize(1);
            assertThat(registros.list.getFirst().getFormattedMessage())
                    .doesNotContain("\n")
                    .contains("x_INFO pagamento aprovado");
        } finally {
            logger.detachAppender(registros);
        }
    }

    @Test
    @DisplayName("JSON ilegível responde texto fixo, sem tipo Java nem trecho do corpo")
    void corpoIlegivelNaoVazaDetalheInterno() {
        var detalheDoJackson = "JSON parse error: Cannot deserialize value of type `java.math.BigDecimal` "
                + "from String \"abc\": not a valid representation";
        var resposta = new TratadorDeErros(java.time.Clock.systemUTC()).corpoIlegivel(
                new org.springframework.http.converter.HttpMessageNotReadableException(detalheDoJackson,
                        new org.springframework.mock.http.MockHttpInputMessage(new byte[0])),
                new MockHttpServletRequest("POST", "/pedidos"));

        assertThat(resposta.getStatus()).isEqualTo(400);
        assertThat(resposta.getDetail()).doesNotContain("java.math", "BigDecimal", "abc");
    }

    @Test
    @DisplayName("regra do domínio violada continua chegando ao cliente")
    void regraDoDominioChegaAoCliente() {
        var resposta = new TratadorDeErros(java.time.Clock.systemUTC()).regraVioladaNaEntrada(
                new IllegalArgumentException("pedido precisa de ao menos um item"),
                new MockHttpServletRequest("POST", "/pedidos"));

        assertThat(resposta.getStatus()).isEqualTo(400);
        assertThat(resposta.getDetail()).isEqualTo("pedido precisa de ao menos um item");
    }
}
