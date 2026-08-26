package br.com.feira.pedidos.aplicacao;

import br.com.feira.contratos.ItemDoPedido;
import br.com.feira.pedidos.dominio.Pedido;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Cria o pedido e abre a saga.
 *
 * <h2>Chave de idempotencia</h2>
 * O cliente manda {@code Idempotency-Key} no cabecalho. Rede cai, botao e
 * clicado duas vezes, aplicativo tenta de novo; sem a chave, cada tentativa
 * vira um pedido e o cliente e cobrado tres vezes.
 *
 * A chave e gravada com restricao de unicidade <b>na mesma transacao</b> do
 * pedido. A segunda chamada com a mesma chave esbarra na restricao, e a
 * resposta e o pedido que ja existe, com 200 em vez de 201.
 *
 * A consulta previa nao dispensa a restricao: duas requisicoes simultaneas
 * passam pela consulta antes de qualquer uma gravar. Quem garante e o banco.
 *
 * <h2>Por que esta classe nao e transacional</h2>
 * Porque ela precisa <b>sobreviver</b> a violacao da restricao para devolver o
 * pedido vencedor. Uma vez que a restricao estoura, a transacao esta condenada
 * e o contexto de persistencia nao serve mais para consultar nada. Por isso o
 * limite transacional fica em {@link GravadorDePedido} e a recuperacao acontece
 * aqui fora, com uma leitura em transacao nova.
 */
@Service
public class CriarPedido {

    private final GravadorDePedido gravador;

    CriarPedido(GravadorDePedido gravador) {
        this.gravador = gravador;
    }

    public record Entrada(String clienteId, List<ItemDoPedido> itens, String chaveDeIdempotencia) {
    }

    /**
     * @param jaExistia verdadeiro quando a chave de idempotencia reaproveitou
     *                  um pedido anterior. A camada HTTP usa isso para
     *                  responder 200 no lugar de 201.
     */
    public record Saida(Pedido pedido, boolean jaExistia) {
    }

    public Saida executar(Entrada entrada) {
        // Consulta previa: atende o caso comum, que e a repeticao chegando
        // depois da primeira ter terminado. Nao substitui a restricao.
        var existente = gravador.porChave(entrada);
        if (existente != null) {
            return new Saida(existente, true);
        }

        try {
            return new Saida(gravador.gravar(entrada), false);

        } catch (DataIntegrityViolationException e) {
            // Corrida: outra requisicao com a mesma chave venceu enquanto esta
            // gravava. O pedido dela e a resposta certa para as duas.
            var vencedor = gravador.porChave(entrada);
            if (vencedor != null) {
                return new Saida(vencedor, true);
            }
            // Violacao de outra restricao qualquer: nao e nosso caso, sobe.
            throw e;
        }
    }
}
