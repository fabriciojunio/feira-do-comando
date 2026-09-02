package br.com.feira.plataforma.outbox;

import br.com.feira.contratos.Evento;
import br.com.feira.plataforma.mensageria.MapeadorDeEventos;
import br.com.feira.plataforma.observabilidade.RastroDaMensagem;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Ponto de entrada para gravar evento no outbox.
 *
 * Sempre chamado de dentro de uma transacao que ja esta alterando estado. Se
 * alguem chamar isto fora de transacao, o evento e gravado sozinho e a
 * garantia do padrao se perde, que e o unico jeito de errar com outbox.
 */
@Component
public class Outbox implements RegistroDeSaida {

    private final OutboxRepository repositorio;
    private final MapeadorDeEventos mapeador;
    private final Clock relogio;
    private final RastroDaMensagem rastro;

    public Outbox(OutboxRepository repositorio, MapeadorDeEventos mapeador, Clock relogio,
                  RastroDaMensagem rastro) {
        this.repositorio = repositorio;
        this.mapeador = mapeador;
        this.relogio = relogio;
        this.rastro = rastro;
    }

    @Override
    public void gravar(String topico, Evento evento) {
        repositorio.save(paraMensagem(topico, evento));
    }

    public void gravarTodos(String topico, List<? extends Evento> eventos) {
        if (eventos.isEmpty()) {
            return;
        }
        repositorio.saveAll(eventos.stream().map(e -> paraMensagem(topico, e)).toList());
    }

    private MensagemDoOutbox paraMensagem(String topico, Evento evento) {
        return MensagemDoOutbox.nova(
                evento.id(),
                topico,
                evento.correlacao().toString(),
                evento.nome(),
                mapeador.paraJson(evento),
                relogio.instant(),
                // Capturado aqui, e nao na publicacao: aqui ainda existe o
                // contexto da requisicao que originou o evento. Na publicacao
                // ela ja terminou.
                rastro.capturar());
    }
}
