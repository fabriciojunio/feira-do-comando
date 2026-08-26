package br.com.feira.plataforma.inbox;

import br.com.feira.contratos.Evento;

/** Memoria do que ja foi consumido, para nao aplicar o mesmo efeito duas vezes. */
public interface RegistroDeEntrada {

    boolean jaProcessou(Evento evento);

    void registrar(Evento evento, String resultado);
}
