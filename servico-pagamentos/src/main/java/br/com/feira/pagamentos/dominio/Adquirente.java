package br.com.feira.pagamentos.dominio;

import br.com.feira.contratos.Dinheiro;
import br.com.feira.contratos.PagamentoRecusado;

import java.util.UUID;

/**
 * A porta para o mundo dos cartoes.
 *
 * Nao existe integracao real aqui, e isso esta dito em vez de escondido. O que
 * existe e a fronteira desenhada do jeito certo: o dominio conversa com esta
 * interface, e trocar por Cielo, Stone ou Adyen e escrever outra classe. O que
 * o projeto demonstra e a saga, e para isso o comportamento do adquirente
 * importa mais que a marca dele.
 */
public interface Adquirente {

    Resposta autorizar(UUID pedidoId, Dinheiro valor);

    void estornar(String referenciaExterna, Dinheiro valor);

    /**
     * @param referenciaExterna identificador da transacao no adquirente. E por
     *                          ele que a conciliacao acontece, entao ele e
     *                          guardado mesmo quando a cobranca e recusada.
     */
    record Resposta(boolean autorizada, String referenciaExterna,
                    PagamentoRecusado.Motivo motivo) {

        public static Resposta aprovada(String referencia) {
            return new Resposta(true, referencia, null);
        }

        public static Resposta recusada(PagamentoRecusado.Motivo motivo, String referencia) {
            return new Resposta(false, referencia, motivo);
        }
    }
}
