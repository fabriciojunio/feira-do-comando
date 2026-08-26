package br.com.feira.plataforma.inbox;

import br.com.feira.contratos.Evento;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Guarda de idempotencia do consumidor.
 *
 * Uso esperado, sempre dentro da mesma transacao do efeito:
 *
 * <pre>{@code
 * if (inbox.jaProcessou(evento)) {
 *     return;
 * }
 * ... aplica o efeito ...
 * inbox.registrar(evento, "resumo do que aconteceu");
 * }</pre>
 *
 * A checagem sozinha nao basta: duas threads podem passar por ela ao mesmo
 * tempo. O que de fato garante e a chave primaria no registro. A consulta
 * previa existe so para evitar o custo de tentar e falhar no caso comum.
 */
@Component
public class Inbox implements RegistroDeEntrada {

    private final EventoProcessadoRepository repositorio;
    private final Clock relogio;

    public Inbox(EventoProcessadoRepository repositorio, Clock relogio) {
        this.repositorio = repositorio;
        this.relogio = relogio;
    }

    @Override
    public boolean jaProcessou(Evento evento) {
        return repositorio.existsById(evento.id());
    }

    @Override
    public void registrar(Evento evento, String resultado) {
        repositorio.save(new EventoProcessado(evento.id(), evento.nome(), evento.correlacao(),
                resultado, relogio.instant()));
    }

    /**
     * Expurgo por retencao. Trinta dias cobre com folga qualquer reentrega
     * plausivel do broker; guardar para sempre so faz a tabela crescer.
     */
    public int expurgar(Instant limite) {
        return repositorio.apagarAntesDe(limite);
    }
}
