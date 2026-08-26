package br.com.feira.pedidos.api;

import br.com.feira.contratos.ItemDoPedido;
import br.com.feira.pedidos.aplicacao.CancelarPedido;
import br.com.feira.pedidos.aplicacao.CriarPedido;
import br.com.feira.pedidos.aplicacao.portas.Portas;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * API de pedidos.
 *
 * <h2>Cabecalhos que importam</h2>
 * <ul>
 *   <li>{@code X-Cliente-Id}: identifica o dono. Num sistema real vem do token
 *       validado; aqui vem no cabecalho para manter o exemplo focado na saga,
 *       e isso esta dito no README em vez de escondido.</li>
 *   <li>{@code Idempotency-Key}: opcional, mas fortemente recomendado. Sem
 *       ele, cliente com rede ruim cria pedido duplicado.</li>
 * </ul>
 *
 * <h2>201 e 200</h2>
 * Criar devolve 201 com {@code Location}. Repetir a mesma chave devolve 200 com
 * o mesmo corpo, e nao 201 nem 409: do ponto de vista do cliente a operacao
 * deu certo, e ele nao precisa saber que ja tinha dado.
 */
@RestController
@RequestMapping("/api/v1/pedidos")
public class PedidoController {

    private static final int TAMANHO_MAXIMO_DA_PAGINA = 100;

    private final CriarPedido criar;
    private final CancelarPedido cancelar;
    private final Portas.DePedidos pedidos;

    public PedidoController(CriarPedido criar, CancelarPedido cancelar, Portas.DePedidos pedidos) {
        this.criar = criar;
        this.cancelar = cancelar;
        this.pedidos = pedidos;
    }

    @PostMapping
    public ResponseEntity<PedidoResposta> criar(
            @RequestHeader("X-Cliente-Id") String clienteId,
            @RequestHeader(name = "Idempotency-Key", required = false) String chave,
            @Valid @RequestBody NovoPedido corpo) {

        var itens = corpo.itens().stream().map(NovoItem::paraContrato).toList();
        var saida = criar.executar(new CriarPedido.Entrada(clienteId, itens, chave));
        var resposta = PedidoResposta.de(saida.pedido());

        if (saida.jaExistia()) {
            return ResponseEntity.ok(resposta);
        }
        return ResponseEntity
                .created(URI.create("/api/v1/pedidos/" + saida.pedido().id()))
                .body(resposta);
    }

    @GetMapping("/{id}")
    public PedidoResposta consultar(@RequestHeader("X-Cliente-Id") String clienteId,
                                    @PathVariable UUID id) {
        var pedido = pedidos.porId(id)
                .filter(p -> p.clienteId().equals(clienteId))
                .orElseThrow(() -> new NoSuchElementException("pedido nao encontrado"));
        return PedidoResposta.de(pedido);
    }

    @GetMapping
    public PaginaDePedidos listar(@RequestHeader("X-Cliente-Id") String clienteId,
                                  @RequestParam(defaultValue = "0") int pagina,
                                  @RequestParam(defaultValue = "20") int tamanho) {
        var limite = Math.min(Math.max(tamanho, 1), TAMANHO_MAXIMO_DA_PAGINA);
        var resultado = pedidos.doCliente(clienteId, PageRequest.of(Math.max(pagina, 0), limite));

        return new PaginaDePedidos(
                resultado.getContent().stream().map(PedidoResposta::de).toList(),
                resultado.getNumber(),
                resultado.getSize(),
                resultado.getTotalElements(),
                resultado.getTotalPages());
    }

    @DeleteMapping("/{id}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.OK)
    public PedidoResposta cancelar(@RequestHeader("X-Cliente-Id") String clienteId,
                                   @PathVariable UUID id) {
        return PedidoResposta.de(cancelar.executar(id, clienteId));
    }

    public record NovoItem(
            @jakarta.validation.constraints.NotBlank String sku,
            @jakarta.validation.constraints.NotBlank String descricao,
            @jakarta.validation.constraints.Min(1) int quantidade,
            @jakarta.validation.constraints.NotBlank String precoUnitario) {

        ItemDoPedido paraContrato() {
            return new ItemDoPedido(sku, descricao, quantidade,
                    br.com.feira.contratos.Dinheiro.de(precoUnitario));
        }
    }

    public record NovoPedido(
            @jakarta.validation.constraints.NotEmpty
            @jakarta.validation.constraints.Size(max = 50, message = "no maximo 50 itens por pedido")
            @Valid java.util.List<NovoItem> itens) {
    }

    public record PaginaDePedidos(java.util.List<PedidoResposta> conteudo, int pagina, int tamanho,
                                  long total, int paginas) {
    }
}
