package br.com.feira.pedidos.dominio;

import br.com.feira.contratos.Evento;

import java.util.List;

/**
 * O que a saga decidiu fazer diante de um evento.
 *
 * A decisao e um valor, nao um efeito. O agregado nao publica em Kafka nem
 * grava em banco: ele diz o que deve acontecer, e a camada de aplicacao
 * executa. Isso permite testar a maquina de estados inteira sem subir nada,
 * que e o unico jeito de cobrir com honestidade as combinacoes de evento fora
 * de ordem, repetido e atrasado.
 *
 * @param mudouDeEstado falso quando o evento nao produziu efeito, o caso comum
 *                      de reentrega
 * @param eventosASeguir eventos a publicar, em ordem
 * @param observacao motivo legivel, usado em log e no historico do pedido
 */
public record Decisao(boolean mudouDeEstado, List<Evento> eventosASeguir, String observacao) {

    public Decisao {
        eventosASeguir = List.copyOf(eventosASeguir);
    }

    /** Nada a fazer: evento repetido, tardio ou irrelevante para o estado atual. */
    public static Decisao ignorar(String observacao) {
        return new Decisao(false, List.of(), observacao);
    }

    public static Decisao avancar(String observacao, Evento... eventos) {
        return new Decisao(true, List.of(eventos), observacao);
    }

    public boolean produziuEventos() {
        return !eventosASeguir.isEmpty();
    }
}
