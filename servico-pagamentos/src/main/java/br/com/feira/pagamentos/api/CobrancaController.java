package br.com.feira.pagamentos.api;

import br.com.feira.pagamentos.dominio.Cobranca;
import br.com.feira.pagamentos.infraestrutura.persistencia.CobrancaRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Consulta de cobrancas.
 *
 * So leitura. Cobrar por HTTP permitiria cobrar um pedido que nao existe, ou
 * cobrar duas vezes o mesmo, e nao ha caso de uso que justifique isso: quem
 * decide cobrar e a saga.
 */
@RestController
@RequestMapping("/api/v1/cobrancas")
public class CobrancaController {

    private final CobrancaRepository cobrancas;

    public CobrancaController(CobrancaRepository cobrancas) {
        this.cobrancas = cobrancas;
    }

    public record CobrancaVista(UUID pedidoId, String situacao, long valorCentavos, String valor,
                                String referenciaExterna, String motivoDaRecusa,
                                Instant criadaEm, Instant encerradaEm) {

        static CobrancaVista de(Cobranca c) {
            return new CobrancaVista(c.pedidoId(), c.situacao().name(), c.valor().centavos(),
                    c.valor().formatado(), c.referenciaExterna(),
                    c.motivoDaRecusa() == null ? null : c.motivoDaRecusa().name(),
                    c.criadaEm(), c.encerradaEm());
        }
    }

    @GetMapping("/{pedidoId}")
    public CobrancaVista consultar(@PathVariable UUID pedidoId) {
        return cobrancas.findById(pedidoId).map(CobrancaVista::de)
                .orElseThrow(() -> new NoSuchElementException("cobranca nao encontrada"));
    }

    @GetMapping
    public List<CobrancaVista> listar() {
        return cobrancas.findAll().stream().map(CobrancaVista::de).toList();
    }

    /** Resumo para o painel: quanto entrou, quanto foi recusado, quanto voltou. */
    @GetMapping("/resumo")
    public Map<String, Object> resumo() {
        return Map.of(
                "pendentes", cobrancas.countBySituacao(Cobranca.Situacao.PENDENTE),
                "aprovadas", cobrancas.countBySituacao(Cobranca.Situacao.APROVADA),
                "recusadas", cobrancas.countBySituacao(Cobranca.Situacao.RECUSADA),
                "estornadas", cobrancas.countBySituacao(Cobranca.Situacao.ESTORNADA),
                "totalAprovadoCentavos", cobrancas.totalAprovadoEmCentavos());
    }
}
