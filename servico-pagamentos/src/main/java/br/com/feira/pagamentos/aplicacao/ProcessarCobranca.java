package br.com.feira.pagamentos.aplicacao;

import br.com.feira.contratos.PagamentoAprovado;
import br.com.feira.contratos.PagamentoEstornado;
import br.com.feira.contratos.PagamentoRecusado;
import br.com.feira.contratos.PedidoCriado;
import br.com.feira.contratos.Topicos;
import br.com.feira.pagamentos.dominio.Adquirente;
import br.com.feira.pagamentos.dominio.Cobranca;
import br.com.feira.pagamentos.infraestrutura.persistencia.CobrancaRepository;
import br.com.feira.plataforma.outbox.RegistroDeSaida;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Registra, cobra e estorna.
 *
 * <h2>Por que a cobranca e o ultimo passo da saga</h2>
 * Estoque e barato de compensar: as unidades voltam para a prateleira e
 * ninguem percebe. Dinheiro nao. Estorno tem custo, prazo e cliente
 * telefonando. Por isso a ordem e reservar primeiro e cobrar depois: quando a
 * cobranca acontece, a unica coisa que ainda pode dar errado e a propria
 * cobranca.
 */
@Service
public class ProcessarCobranca {

    private static final Logger log = LoggerFactory.getLogger(ProcessarCobranca.class);

    private final CobrancaRepository cobrancas;
    private final Adquirente adquirente;
    private final RegistroDeSaida saida;
    private final MeterRegistry metricas;
    private final Clock relogio;

    public ProcessarCobranca(CobrancaRepository cobrancas, Adquirente adquirente,
                             RegistroDeSaida saida, MeterRegistry metricas, Clock relogio) {
        this.cobrancas = cobrancas;
        this.adquirente = adquirente;
        this.saida = saida;
        this.metricas = metricas;
        this.relogio = relogio;
    }

    /** Guarda o valor do pedido para cobrar quando o estoque confirmar. */
    @Transactional
    public void registrar(PedidoCriado pedido) {
        if (cobrancas.existsById(pedido.correlacao())) {
            return;
        }
        cobrancas.save(Cobranca.pendente(pedido.correlacao(), pedido.total(), relogio.instant()));
        log.debug("Cobranca de {} registrada em {}", pedido.correlacao(),
                pedido.total().formatado());
    }

    /**
     * Chama o adquirente e publica o desfecho.
     *
     * A chamada externa fica dentro da transacao de proposito, apesar de ser
     * uma pratica que se costuma evitar. O motivo: se a autorizacao passar e a
     * gravacao falhar, existe dinheiro cobrado sem registro nenhum, que e o
     * pior desfecho possivel. Segurar a transacao pelo tempo da chamada custa
     * conexao de banco; o contrario custa dinheiro de cliente.
     */
    @Transactional
    public void cobrar(UUID pedidoId) {
        var cobranca = cobrancas.findById(pedidoId).orElse(null);
        if (cobranca == null) {
            throw new br.com.feira.plataforma.mensageria.Falhas.AguardandoDadoAnterior(
                    "cobranca do pedido " + pedidoId);
        }
        if (!cobranca.pendente()) {
            log.debug("Cobranca de {} ja estava {}", pedidoId, cobranca.situacao());
            return;
        }

        var agora = relogio.instant();
        var resposta = adquirente.autorizar(pedidoId, cobranca.valor());

        if (resposta.autorizada()) {
            cobranca.aprovar(resposta.referenciaExterna(), agora);
            cobrancas.save(cobranca);
            saida.gravar(Topicos.PAGAMENTOS, PagamentoAprovado.de(pedidoId, cobranca.id(),
                    resposta.referenciaExterna(), cobranca.valor(), agora));
            metricas.counter("feira.pagamentos.aprovados").increment();
            log.info("Pagamento do pedido {} aprovado ({})", pedidoId, cobranca.valor().formatado());
            return;
        }

        cobranca.recusar(resposta.motivo(), resposta.referenciaExterna(), agora);
        cobrancas.save(cobranca);
        saida.gravar(Topicos.PAGAMENTOS,
                PagamentoRecusado.de(pedidoId, cobranca.id(), resposta.motivo(), agora));
        metricas.counter("feira.pagamentos.recusados", "motivo", resposta.motivo().name())
                .increment();
        log.info("Pagamento do pedido {} recusado: {}", pedidoId, resposta.motivo());
    }

    /** Compensacao: devolve o dinheiro e avisa. */
    @Transactional
    public void estornar(UUID pedidoId, String motivo) {
        var cobranca = cobrancas.findById(pedidoId).orElse(null);
        if (cobranca == null || !cobranca.aprovada()) {
            // Nada a estornar. Acontece o tempo todo: o pedido cancela antes
            // de a cobranca ter sido aprovada, e o aviso chega mesmo assim.
            return;
        }

        var agora = relogio.instant();
        adquirente.estornar(cobranca.referenciaExterna(), cobranca.valor());
        cobranca.estornar(agora);
        cobrancas.save(cobranca);

        saida.gravar(Topicos.PAGAMENTOS, PagamentoEstornado.de(pedidoId, cobranca.id(),
                cobranca.valor(), motivo, agora));
        metricas.counter("feira.pagamentos.estornos").increment();
        log.info("Pagamento do pedido {} estornado: {}", pedidoId, motivo);
    }
}
