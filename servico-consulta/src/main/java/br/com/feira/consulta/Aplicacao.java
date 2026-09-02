package br.com.feira.consulta;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.sql.init.SqlInitializationAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Servico de consulta.
 *
 * <p>Nao tem regra de negocio e nao decide nada: le evento e monta documento.
 * Se ele cair, os pedidos continuam sendo processados; o que para e a leitura,
 * e ela volta sozinha quando o consumidor reposiciona no topico.
 */
/*
 * O escopo de varredura pega so o pacote de mensageria da plataforma, e nao a
 * plataforma inteira.
 *
 * O outbox e a caixa de entrada de la dependem de JPA, e este servico nao tem
 * banco relacional: a protecao contra evento repetido mora no proprio
 * documento, que a projecao ja precisa gravar de qualquer forma. Varrer tudo
 * arrastaria repositorio JPA para dentro de um contexto que nao tem
 * DataSource, e o servico simplesmente nao subiria.
 */
/*
 * JPA fica desligado explicitamente.
 *
 * A plataforma traz o starter de JPA por transitividade, por causa do outbox
 * dos outros servicos. Aqui nao existe banco relacional, e sem esta exclusao o
 * Spring tenta montar um DataSource, nao acha driver e o servico nao sobe.
 * Desligar aqui e melhor do que remover a dependencia da plataforma, que os
 * outros tres modulos usam de verdade.
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class,
        SqlInitializationAutoConfiguration.class})
@ComponentScan(basePackages = {
        "br.com.feira.consulta",
        "br.com.feira.plataforma.mensageria",
        // Só a observabilidade da plataforma, e não ela inteira: este serviço
        // não tem banco relacional, então outbox e inbox não fazem sentido
        // aqui e arrastariam JPA junto.
        "br.com.feira.plataforma.observabilidade"})
public class Aplicacao {

    public static void main(String[] args) {
        SpringApplication.run(Aplicacao.class, args);
    }
}
