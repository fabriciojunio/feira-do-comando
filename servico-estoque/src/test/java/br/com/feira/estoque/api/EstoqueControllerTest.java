package br.com.feira.estoque.api;

import br.com.feira.estoque.infraestrutura.persistencia.ItemDeEstoqueRepository;
import br.com.feira.estoque.infraestrutura.persistencia.ReservaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Entrada ruim responde com erro do cliente, e não com 500.
 *
 * <p>O primeiro DAST, em 02/11/2026, achou SKU inexistente devolvendo 500 e
 * SKU maior que a coluna estourando no banco.
 */
@DisplayName("API do estoque")
class EstoqueControllerTest {

    private final ItemDeEstoqueRepository itens = mock(ItemDeEstoqueRepository.class);
    private final ReservaRepository reservas = mock(ReservaRepository.class);
    private MockMvc mvc;

    @BeforeEach
    void montar() {
        mvc = MockMvcBuilders.standaloneSetup(new EstoqueController(itens, reservas, Clock.systemUTC()))
                .build();
    }

    @Test
    @DisplayName("SKU que não existe responde 404")
    void skuInexistente() throws Exception {
        when(itens.findById("nao-existe")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/estoque/itens/nao-existe")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("reserva que não existe responde 404")
    void reservaInexistente() throws Exception {
        var pedido = UUID.randomUUID();
        when(reservas.findById(pedido)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/estoque/reservas/" + pedido)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("SKU maior que a coluna é recusado antes de chegar ao banco")
    void skuGrandeDemais() throws Exception {
        var corpo = """
                {"sku":"%s","descricao":"Queijo","quantidade":1}""".formatted("S".repeat(61));

        mvc.perform(post("/api/v1/estoque/itens").contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
        verify(itens, never()).save(any());
    }

    @Test
    @DisplayName("descrição maior que a coluna é recusada antes de chegar ao banco")
    void descricaoGrandeDemais() throws Exception {
        var corpo = """
                {"sku":"QJ-1","descricao":"%s","quantidade":1}""".formatted("d".repeat(201));

        mvc.perform(post("/api/v1/estoque/itens").contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
        verify(itens, never()).save(any());
    }
}
