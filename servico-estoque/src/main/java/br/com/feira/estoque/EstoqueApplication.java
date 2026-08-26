package br.com.feira.estoque;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Servico de estoque.
 *
 * As tres anotacoes de varredura existem porque o outbox e o inbox moram no
 * modulo compartilhado, fora do pacote raiz deste servico.
 */
@SpringBootApplication
@EnableScheduling
@EntityScan(basePackages = {"br.com.feira.estoque", "br.com.feira.plataforma"})
@EnableJpaRepositories(basePackages = {"br.com.feira.estoque", "br.com.feira.plataforma"})
@ComponentScan(basePackages = {"br.com.feira.estoque", "br.com.feira.plataforma"})
public class EstoqueApplication {

    public static void main(String[] args) {
        SpringApplication.run(EstoqueApplication.class, args);
    }

    @Bean
    public Clock relogio() {
        return Clock.systemUTC();
    }
}
