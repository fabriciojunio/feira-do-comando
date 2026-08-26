package br.com.feira.pedidos.infraestrutura.persistencia;

import br.com.feira.pedidos.aplicacao.portas.Portas;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Liga a porta de chaves de idempotencia a tabela.
 *
 * Existe como adaptador, e nao como interface do Spring Data implementando a
 * porta direto, porque a porta fala em {@code UUID} e a tabela fala em
 * entidade. Traduzir aqui e o que impede a entidade de vazar para a camada de
 * aplicacao e recriar o ciclo entre pacotes.
 */
@Component
public class AdaptadorDeChaves implements Portas.DeChaves {

    private final ChaveDeIdempotenciaRepository repositorio;

    public AdaptadorDeChaves(ChaveDeIdempotenciaRepository repositorio) {
        this.repositorio = repositorio;
    }

    @Override
    public Optional<UUID> pedidoDaChave(String chave, String clienteId) {
        return repositorio.findByChaveAndClienteId(chave, clienteId)
                .map(ChaveDeIdempotencia::pedidoId);
    }

    @Override
    public void registrar(String chave, String clienteId, UUID pedidoId, Instant agora) {
        repositorio.save(new ChaveDeIdempotencia(chave, clienteId, pedidoId, agora));
    }
}
