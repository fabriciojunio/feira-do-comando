package br.com.feira.consulta.projecao;

import br.com.feira.contratos.Topicos;
import br.com.feira.plataforma.mensageria.MapeadorDeEventos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Escuta os tres topicos e alimenta a projecao.
 *
 * <p>Este consumidor le os mesmos eventos que os servicos de negocio leem, mas
 * com grupo proprio. Isso e o que permite reconstruir a projecao inteira do
 * zero: basta apagar a colecao e reposicionar o grupo no inicio do topico, sem
 * tocar em nenhum dos outros servicos.
 *
 * <p>Ele nao usa a caixa de entrada da plataforma de proposito. A protecao
 * contra evento repetido mora no proprio documento, em
 * {@code eventosAplicados}: a projecao ja precisa guardar o que aplicou para
 * nao duplicar a linha do tempo, e uma segunda tabela de controle seria a
 * mesma informacao em dois lugares.
 */
@Component
public class ConsumidorDeEventos {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorDeEventos.class);

    private final MapeadorDeEventos mapeador;
    private final Projetor projetor;

    public ConsumidorDeEventos(MapeadorDeEventos mapeador, Projetor projetor) {
        this.mapeador = mapeador;
        this.projetor = projetor;
    }

    @KafkaListener(
            topics = {Topicos.PEDIDOS, Topicos.ESTOQUE, Topicos.PAGAMENTOS},
            groupId = "consulta")
    public void receber(String carga) throws Exception {
        var evento = mapeador.deJson(carga);
        projetor.aplicar(evento).ifPresent(pedido ->
                log.debug("Projecao do pedido {} na versao {}", pedido.getId(), pedido.getVersao()));
    }
}
