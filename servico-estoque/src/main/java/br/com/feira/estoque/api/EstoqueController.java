package br.com.feira.estoque.api;

import br.com.feira.estoque.dominio.ItemDeEstoque;
import br.com.feira.estoque.dominio.Reserva;
import br.com.feira.estoque.infraestrutura.persistencia.ItemDeEstoqueRepository;
import br.com.feira.estoque.infraestrutura.persistencia.ReservaRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Operacao do estoque: consulta de saldo e reposicao.
 *
 * A reserva nao entra aqui de proposito. Ela so acontece por evento, vinda do
 * pedido. Um endereco HTTP que reserva permitiria segurar mercadoria sem
 * pedido nenhum, e ninguem saberia quando devolver.
 */
@RestController
@RequestMapping("/api/v1/estoque")
public class EstoqueController {

    private final ItemDeEstoqueRepository itens;
    private final ReservaRepository reservas;
    private final Clock relogio;

    public EstoqueController(ItemDeEstoqueRepository itens, ReservaRepository reservas,
                             Clock relogio) {
        this.itens = itens;
        this.reservas = reservas;
        this.relogio = relogio;
    }

    public record SaldoVisto(String sku, String descricao, int disponivel, int reservado,
                             int vendido, int emMaos) {

        static SaldoVisto de(ItemDeEstoque item) {
            return new SaldoVisto(item.sku(), item.descricao(), item.disponivel(),
                    item.reservado(), item.vendido(), item.emMaos());
        }
    }

    @GetMapping("/itens")
    public List<SaldoVisto> listar() {
        return itens.findAll().stream().map(SaldoVisto::de).toList();
    }

    @GetMapping("/itens/{sku}")
    public SaldoVisto consultar(@PathVariable String sku) {
        return itens.findById(sku).map(SaldoVisto::de)
                .orElseThrow(() -> new NoSuchElementException("sku nao encontrado"));
    }

    /** Itens perto de acabar, para quem cuida da reposicao. */
    @GetMapping("/reposicao")
    public List<SaldoVisto> reposicao(@RequestParam(defaultValue = "5") int limite) {
        return itens.abaixoDe(limite).stream().map(SaldoVisto::de).toList();
    }

    public record NovoItem(@NotBlank String sku, @NotBlank String descricao,
                           @Min(0) int quantidade) {
    }

    /**
     * Cadastra ou repoe. Repor um SKU existente soma ao saldo em vez de
     * sobrescrever: sobrescrever apagaria a reserva em andamento e o saldo
     * ficaria mentindo.
     */
    @PostMapping("/itens")
    @Transactional
    public ResponseEntity<SaldoVisto> cadastrar(@Valid @RequestBody NovoItem novo) {
        var existente = itens.findById(novo.sku());
        if (existente.isPresent()) {
            var item = existente.get();
            if (novo.quantidade() > 0) {
                item.repor(novo.quantidade(), relogio.instant());
            }
            return ResponseEntity.ok(SaldoVisto.de(itens.save(item)));
        }
        var item = new ItemDeEstoque(novo.sku(), novo.descricao(), novo.quantidade(),
                relogio.instant());
        return ResponseEntity.status(HttpStatus.CREATED).body(SaldoVisto.de(itens.save(item)));
    }

    public record ReservaVista(String pedidoId, String situacao, List<String> linhas) {
    }

    @GetMapping("/reservas/{pedidoId}")
    public ReservaVista reserva(@PathVariable UUID pedidoId) {
        return reservas.findById(pedidoId)
                .map(r -> new ReservaVista(r.pedidoId().toString(), r.situacao().name(),
                        r.linhas().stream().map(l -> l.sku() + " x" + l.quantidade()).toList()))
                .orElseThrow(() -> new NoSuchElementException("reserva nao encontrada"));
    }

    @GetMapping("/reservas")
    public Map<String, Long> resumo() {
        return Map.of(
                "ativas", reservas.countBySituacao(Reserva.Situacao.ATIVA),
                "liberadas", reservas.countBySituacao(Reserva.Situacao.LIBERADA),
                "baixadas", reservas.countBySituacao(Reserva.Situacao.BAIXADA),
                "unidadesReservadas", itens.totalReservado());
    }
}
