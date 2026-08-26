package br.com.feira.plataforma.mensageria;

import br.com.feira.contratos.Evento;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.stereotype.Component;

/**
 * Serializacao dos eventos, isolada da serializacao da API.
 *
 * <h2>Por que nao e um bean de {@code ObjectMapper}</h2>
 * Essa foi a primeira tentativa, e ela quebrou a API inteira de um jeito nada
 * obvio. O {@code JacksonAutoConfiguration} do Spring Boot desiste de criar o
 * mapeador dele assim que enxerga <em>qualquer</em> bean de
 * {@code ObjectMapper}. Junto com o mapeador vao embora as customizacoes que o
 * Boot registra, entre elas o mixin que achata as extensoes do
 * {@code ProblemDetail}. O resultado: as respostas de erro passaram a sair com
 * os campos aninhados dentro de {@code properties}, fora do que a RFC 9457
 * descreve, sem nenhum erro no log.
 *
 * Encapsular o mapeador dentro de um tipo proprio resolve: a API continua com
 * o mapeador do Boot, e o evento tem o seu, com regras que so mudam quando o
 * contrato muda.
 */
@Component
public class MapeadorDeEventos {

    private final ObjectMapper json = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            // Data em ISO-8601, e nao em epoch. Mensagem no topico e lida por
            // gente durante investigacao, e numero nao ajuda ninguem.
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    public String paraJson(Evento evento) {
        try {
            return json.writeValueAsString(evento);
        } catch (Exception e) {
            // Serializar evento proprio nao deve falhar. Se falhou, o contrato
            // ficou incoerente e nao ha como seguir sem perder a mensagem.
            throw new IllegalStateException(
                    "nao foi possivel serializar o evento " + evento.nome(), e);
        }
    }

    public Evento deJson(String carga) throws Exception {
        return json.readValue(carga, Evento.class);
    }
}
