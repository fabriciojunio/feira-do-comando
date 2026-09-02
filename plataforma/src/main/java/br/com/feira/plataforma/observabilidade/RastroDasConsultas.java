package br.com.feira.plataforma.observabilidade;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.MethodExecutionContext;
import net.ttddyy.dsproxy.listener.MethodExecutionListener;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Locale;

/**
 * Um trecho de rastro por consulta ao banco.
 *
 * <p>Fecha a limitação que o documento 0008 assumiu: o rastro dizia que o
 * consumo do catálogo levou 800 ms, e não qual consulta dentro dele. Com isto,
 * a linha do tempo do pedido passa a mostrar as idas ao banco por dentro de
 * cada trecho de serviço.
 *
 * <p>Envolve o {@code DataSource} em vez de instrumentar repositório por
 * repositório, o que pega também o que o Hibernate emite por conta própria:
 * carregamento tardio, consulta de conferência de versão, e o famoso problema
 * de uma consulta virar N. Esse último, aliás, é o motivo mais forte para isto
 * existir: N mais um só aparece quando alguém vê as consultas repetidas
 * enfileiradas no painel.
 *
 * <h2>O que não vai para o trecho</h2>
 * O valor dos parâmetros. Um trecho de rastro é telemetria, sai da aplicação e
 * fica guardado em outro sistema; CPF, e-mail e telefone dos empreendedores não
 * têm por que passear por lá. O texto do comando basta para identificar a
 * consulta, e é o que a biblioteca chama de forma preparada.
 */
@Component
@ConditionalOnProperty(name = "management.tracing.enabled", havingValue = "true")
public class RastroDasConsultas implements BeanPostProcessor {

    /** Limite do texto guardado no trecho. Consulta gerada por ORM é enorme. */
    private static final int LIMITE_DO_TEXTO = 500;

    private final Tracer tracer;

    public RastroDasConsultas(Tracer tracer) {
        this.tracer = tracer;
    }

    /**
     * Troca o {@code DataSource} do contexto por um que avisa a cada consulta.
     *
     * <p>Um pós-processador de bean, e não um {@code @Bean} novo, porque o
     * DataSource é criado pelo Spring Boot a partir das propriedades e
     * recriá-lo aqui significaria repetir toda essa configuração, incluindo o
     * pool de conexões.
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String nome) {
        if (!(bean instanceof DataSource original)) {
            return bean;
        }
        return ProxyDataSourceBuilder.create(original)
                .name("feira")
                .listener(porConsulta())
                .methodListener(semRuido())
                .build();
    }

    private QueryExecutionListener porConsulta() {
        return new QueryExecutionListener() {

            @Override
            public void beforeQuery(ExecutionInfo execucao, List<QueryInfo> consultas) {
                // Nada aqui: o trecho é aberto e fechado depois, com a duração
                // que a própria biblioteca mediu. Abrir antes exigiria guardar
                // o trecho por thread, e a conexão pode trocar de thread.
            }

            @Override
            public void afterQuery(ExecutionInfo execucao, List<QueryInfo> consultas) {
                if (consultas.isEmpty()) {
                    return;
                }
                String comando = consultas.get(0).getQuery();

                Span trecho = tracer.nextSpan().name(nomeDoTrecho(comando));
                trecho.tag("db.system", "postgresql");
                trecho.tag("db.statement", resumir(comando));
                trecho.tag("db.duracao_ms", String.valueOf(execucao.getElapsedTime()));
                if (consultas.size() > 1) {
                    trecho.tag("db.comandos_no_lote", String.valueOf(consultas.size()));
                }
                if (execucao.getThrowable() != null) {
                    trecho.error(execucao.getThrowable());
                }
                trecho.start().end();
            }
        };
    }

    /**
     * A biblioteca também avisa sobre commit, rollback e abertura de conexão.
     *
     * <p>Ficam de fora: em fluxo com muitas transações curtas, isso triplica o
     * número de trechos e o painel vira uma parede onde a consulta lenta some
     * no meio.
     */
    private MethodExecutionListener semRuido() {
        return new MethodExecutionListener() {
            @Override
            public void beforeMethod(MethodExecutionContext contexto) {
            }

            @Override
            public void afterMethod(MethodExecutionContext contexto) {
            }
        };
    }

    /**
     * O nome do trecho é a operação e a tabela, e não o comando inteiro.
     *
     * <p>Painel agrupa por nome. Com o comando inteiro no nome, cada consulta
     * vira um grupo de um, e a pergunta "qual consulta está lenta em geral"
     * deixa de ter resposta.
     */
    static String nomeDoTrecho(String comando) {
        String limpo = comando.trim().toLowerCase(Locale.ROOT);
        if (limpo.startsWith("select")) {
            return "db select " + tabelaDepoisDe(limpo, " from ");
        }
        if (limpo.startsWith("insert")) {
            return "db insert " + tabelaDepoisDe(limpo, " into ");
        }
        if (limpo.startsWith("update")) {
            return "db update " + tabelaDepoisDe(limpo, "update ");
        }
        if (limpo.startsWith("delete")) {
            return "db delete " + tabelaDepoisDe(limpo, " from ");
        }
        return "db comando";
    }

    private static String tabelaDepoisDe(String comando, String marca) {
        int inicio = comando.indexOf(marca);
        if (inicio < 0) {
            return "?";
        }
        String resto = comando.substring(inicio + marca.length()).trim();
        int fim = resto.indexOf(' ');
        return fim < 0 ? resto : resto.substring(0, fim);
    }

    static String resumir(String comando) {
        String numaLinha = comando.replaceAll("\\s+", " ").trim();
        return numaLinha.length() <= LIMITE_DO_TEXTO
                ? numaLinha
                : numaLinha.substring(0, LIMITE_DO_TEXTO) + "...";
    }
}
