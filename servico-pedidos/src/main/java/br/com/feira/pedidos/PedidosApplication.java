package br.com.feira.pedidos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
// O outbox e o inbox moram no modulo plataforma, fora do pacote raiz deste
// servico. Sem estas duas anotacoes o Spring nao acha as entidades nem os
// repositorios, e o contexto sobe reclamando de bean ausente.
@org.springframework.boot.autoconfigure.domain.EntityScan(
        basePackages = {"br.com.feira.pedidos", "br.com.feira.plataforma"})
@org.springframework.data.jpa.repository.config.EnableJpaRepositories(
        basePackages = {"br.com.feira.pedidos", "br.com.feira.plataforma"})
@org.springframework.context.annotation.ComponentScan(
        basePackages = {"br.com.feira.pedidos", "br.com.feira.plataforma"})
public class PedidosApplication {

    public static void main(String[] args) {
        SpringApplication.run(PedidosApplication.class, args);
    }

    /**
     * Relogio injetado. Nenhuma regra chama {@code Instant.now()} direto, o que
     * torna prazo de saga e expurgo testaveis sem esperar o tempo passar.
     */
    @Bean
    public Clock relogio() {
        return Clock.systemUTC();
    }

}
