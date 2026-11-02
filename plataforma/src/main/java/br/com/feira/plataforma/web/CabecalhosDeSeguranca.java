package br.com.feira.plataforma.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Cabeçalhos de segurança em toda resposta dos quatro serviços.
 *
 * <p>Os serviços só devolvem JSON e não têm sessão nem cookie, então a lista é
 * curta de propósito. {@code nosniff} impede o navegador de tratar como script
 * um corpo que não é, e {@code same-origin} impede outro site de embutir a
 * resposta. Os dois apareceram ausentes no primeiro DAST, em 02/11/2026.
 *
 * <p>Fica no começo da cadeia para valer também quando a requisição termina em
 * erro antes de chegar ao controlador.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CabecalhosDeSeguranca extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
                                    FilterChain corrente) throws ServletException, IOException {
        resposta.setHeader("X-Content-Type-Options", "nosniff");
        resposta.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        corrente.doFilter(requisicao, resposta);
    }
}
