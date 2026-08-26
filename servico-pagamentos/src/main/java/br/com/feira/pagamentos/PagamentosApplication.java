package br.com.feira.pagamentos;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
@EntityScan(basePackages = {"br.com.feira.pagamentos", "br.com.feira.plataforma"})
@EnableJpaRepositories(basePackages = {"br.com.feira.pagamentos", "br.com.feira.plataforma"})
@ComponentScan(basePackages = {"br.com.feira.pagamentos", "br.com.feira.plataforma"})
public class PagamentosApplication {

    public static void main(String[] args) {
        SpringApplication.run(PagamentosApplication.class, args);
    }

    @Bean
    public Clock relogio() {
        return Clock.systemUTC();
    }
}
