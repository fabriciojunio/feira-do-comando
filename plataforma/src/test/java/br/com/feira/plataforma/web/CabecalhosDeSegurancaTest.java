package br.com.feira.plataforma.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("Cabeçalhos de segurança")
class CabecalhosDeSegurancaTest {

    private MockHttpServletResponse atender(String metodo, String caminho) throws Exception {
        var requisicao = new MockHttpServletRequest(metodo, caminho);
        var resposta = new MockHttpServletResponse();
        var corrente = mock(FilterChain.class);

        new CabecalhosDeSeguranca().doFilter(requisicao, resposta, corrente);

        verify(corrente).doFilter(requisicao, resposta);
        return resposta;
    }

    @Test
    @DisplayName("proíbe o navegador de adivinhar o tipo do conteúdo")
    void semAdivinharTipo() throws Exception {
        assertThat(atender("GET", "/api/v1/pedidos").getHeader("X-Content-Type-Options"))
                .isEqualTo("nosniff");
    }

    @Test
    @DisplayName("só deixa a própria origem embutir a resposta")
    void soAPropriaOrigem() throws Exception {
        assertThat(atender("GET", "/api/v1/pedidos").getHeader("Cross-Origin-Resource-Policy"))
                .isEqualTo("same-origin");
    }

    @Test
    @DisplayName("vale para qualquer método e caminho, inclusive o que dá erro")
    void valeParaTudo() throws Exception {
        var resposta = atender("DELETE", "/caminho/que/nao/existe");

        assertThat(resposta.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(resposta.getHeader("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
    }
}
