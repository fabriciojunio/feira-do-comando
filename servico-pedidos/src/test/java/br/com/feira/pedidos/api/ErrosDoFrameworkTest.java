package br.com.feira.pedidos.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Erro do cliente que o próprio Spring detecta tem que sair com o status dele.
 *
 * <p>O primeiro DAST, em 02/11/2026, achou 28 respostas 500: id que não é UUID,
 * página que não é número, método que a rota não aceita e caminho que não
 * existe. Todas caíam no tratador genérico de erro inesperado.
 */
@DisplayName("Erros detectados pelo framework")
class ErrosDoFrameworkTest {

    @RestController
    static class Rotas {

        @GetMapping("/teste/{id}")
        String porId(@PathVariable UUID id) {
            return id.toString();
        }

        @GetMapping("/teste")
        String pagina(@RequestParam(defaultValue = "0") int pagina) {
            return String.valueOf(pagina);
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void montar() {
        mvc = MockMvcBuilders.standaloneSetup(new Rotas())
                .setControllerAdvice(new TratadorDeErros(Clock.systemUTC()))
                .build();
    }

    @Test
    @DisplayName("id que não é UUID responde 400")
    void idInvalido() throws Exception {
        mvc.perform(get("/teste/id"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.codigo").value("REQUISICAO_INVALIDA"));
    }

    @Test
    @DisplayName("parâmetro numérico com texto responde 400, sem o tipo Java na mensagem")
    void paginaInvalida() throws Exception {
        mvc.perform(get("/teste").param("pagina", "A".repeat(500)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("java."))));
    }

    @Test
    @DisplayName("método que a rota não aceita responde 405")
    void metodoNaoAceito() throws Exception {
        mvc.perform(delete("/teste/" + UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("caminho que não existe responde 404")
    void caminhoInexistente() throws Exception {
        mvc.perform(delete("/latest/meta-data/"))
                .andExpect(status().isNotFound());
    }
}
