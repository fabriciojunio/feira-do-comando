package br.com.feira.consulta.api;

import br.com.feira.consulta.dominio.PedidoLido;
import br.com.feira.consulta.projecao.PedidoLidoRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * A consulta que antes exigia tres chamadas.
 *
 * <p>O painel do cliente pedia o pedido ao servico de pedidos, a cobranca ao
 * de pagamentos e o saldo ao de estoque, e juntava tudo no navegador. Aqui a
 * mesma resposta sai de uma leitura, porque o documento ja esta montado.
 *
 * <p>O servico e somente de leitura. Nao existe POST, PUT nem DELETE, e isso e
 * proposital: quem muda o pedido e o servico de pedidos. Aceitar escrita aqui
 * criaria dois donos para o mesmo dado.
 */
@RestController
@RequestMapping("/api/v1/consulta")
public class ConsultaController {

    /** Teto de itens por pagina. Sem ele, limite=1000000 vira varredura. */
    private static final int MAXIMO_POR_PAGINA = 50;

    private final PedidoLidoRepository repositorio;

    public ConsultaController(PedidoLidoRepository repositorio) {
        this.repositorio = repositorio;
    }

    @GetMapping("/pedidos")
    public Map<String, Object> listar(
            @RequestHeader("X-Cliente-Id") String clienteId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "20") int tamanho) {

        int porPagina = Math.min(Math.max(tamanho, 1), MAXIMO_POR_PAGINA);
        var requisicao = PageRequest.of(Math.max(pagina, 0), porPagina);

        var resultado = status == null || status.isBlank()
                ? repositorio.findByClienteIdOrderByCriadoEmDesc(clienteId, requisicao)
                : repositorio.findByClienteIdAndStatusOrderByCriadoEmDesc(
                        clienteId, status, requisicao);

        return Map.of(
                "conteudo", resultado.getContent(),
                "total", resultado.getTotalElements(),
                "pagina", resultado.getNumber(),
                "tamanho", resultado.getSize());
    }

    /**
     * Um pedido, com itens, cobranca e linha do tempo, numa ida so ao banco.
     *
     * <p>O cliente vai no cabecalho e e conferido contra o dono do documento.
     * Sem essa checagem, saber o id de um pedido bastaria para ler o pedido de
     * outra pessoa, e id de pedido aparece em URL.
     */
    @GetMapping("/pedidos/{id}")
    public ResponseEntity<PedidoLido> porId(
            @RequestHeader("X-Cliente-Id") String clienteId,
            @PathVariable UUID id) {

        return repositorio.findById(id)
                .filter(p -> p.getClienteId().equals(clienteId))
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
