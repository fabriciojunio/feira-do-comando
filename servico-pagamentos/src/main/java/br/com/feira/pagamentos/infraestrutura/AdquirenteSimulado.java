package br.com.feira.pagamentos.infraestrutura;

import br.com.feira.contratos.Dinheiro;
import br.com.feira.contratos.PagamentoRecusado;
import br.com.feira.pagamentos.dominio.Adquirente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Adquirente de mentira, com comportamento de verdade.
 *
 * <h2>Por que nao aprova sempre</h2>
 * Um simulador que aprova tudo faz a saga parecer perfeita e esconde metade do
 * sistema: a compensacao, o estorno e o cancelamento nunca seriam exercitados.
 * As taxas de recusa aqui saem de proporcoes plausiveis de comercio
 * eletronico, e cada motivo leva a saga por um caminho diferente.
 *
 * <h2>Valores que forcam um desfecho</h2>
 * Para demonstracao e teste, alguns valores tem resultado fixo. E um recurso
 * comum em ambiente de homologacao de adquirente de verdade, e serve para
 * mostrar o cancelamento sem depender de sorte:
 *
 * <ul>
 *   <li>termina em 13 centavos: recusa por saldo insuficiente;</li>
 *   <li>termina em 66 centavos: recusa por suspeita de fraude;</li>
 *   <li>termina em 99 centavos: falha temporaria, que permite nova tentativa.</li>
 * </ul>
 */
@Component
public class AdquirenteSimulado implements Adquirente {

    private static final Logger log = LoggerFactory.getLogger(AdquirenteSimulado.class);

    private final int percentualDeRecusa;

    public AdquirenteSimulado(@Value("${feira.pagamentos.percentual-de-recusa:12}")
                              int percentualDeRecusa) {
        this.percentualDeRecusa = percentualDeRecusa;
    }

    @Override
    public Resposta autorizar(UUID pedidoId, Dinheiro valor) {
        var referencia = "tx-" + UUID.randomUUID().toString().substring(0, 12);
        long centavos = valor.centavos() % 100;

        Resposta forcada = switch ((int) centavos) {
            case 13 -> Resposta.recusada(PagamentoRecusado.Motivo.SALDO_INSUFICIENTE, referencia);
            case 66 -> Resposta.recusada(PagamentoRecusado.Motivo.SUSPEITA_DE_FRAUDE, referencia);
            case 99 -> Resposta.recusada(PagamentoRecusado.Motivo.FALHA_TEMPORARIA, referencia);
            default -> null;
        };
        if (forcada != null) {
            log.info("Cobranca do pedido {} recusada por regra de simulacao: {}",
                    pedidoId, forcada.motivo());
            return forcada;
        }

        if (ThreadLocalRandom.current().nextInt(100) < percentualDeRecusa) {
            var motivo = sortearMotivo();
            log.info("Cobranca do pedido {} recusada: {}", pedidoId, motivo);
            return Resposta.recusada(motivo, referencia);
        }

        return Resposta.aprovada(referencia);
    }

    @Override
    public void estornar(String referenciaExterna, Dinheiro valor) {
        log.info("Estorno de {} solicitado para a transacao {}", valor.formatado(),
                referenciaExterna);
    }

    /**
     * Distribuicao aproximada do que se ve em comercio eletronico: a maioria
     * das recusas e saldo, uma parte menor e cartao invalido, e fraude e a
     * fatia menor.
     */
    private static PagamentoRecusado.Motivo sortearMotivo() {
        int sorteio = ThreadLocalRandom.current().nextInt(100);
        if (sorteio < 60) {
            return PagamentoRecusado.Motivo.SALDO_INSUFICIENTE;
        }
        if (sorteio < 85) {
            return PagamentoRecusado.Motivo.CARTAO_INVALIDO;
        }
        if (sorteio < 95) {
            return PagamentoRecusado.Motivo.FALHA_TEMPORARIA;
        }
        return PagamentoRecusado.Motivo.SUSPEITA_DE_FRAUDE;
    }
}
