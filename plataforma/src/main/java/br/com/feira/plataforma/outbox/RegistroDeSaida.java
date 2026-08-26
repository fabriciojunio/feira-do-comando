package br.com.feira.plataforma.outbox;

import br.com.feira.contratos.Evento;

/**
 * Grava evento para publicacao posterior.
 *
 * O nome nao e "publicar" de proposito: nada e publicado na chamada. O evento
 * e gravado na mesma transacao da mudanca de estado, e um processo separado
 * envia. Chamar de publicar induziria quem le a esperar que a mensagem ja
 * esteja no broker quando o metodo retorna, e essa suposicao errada e o jeito
 * mais comum de anular a garantia do padrao.
 */
public interface RegistroDeSaida {

    void gravar(String topico, Evento evento);
}
