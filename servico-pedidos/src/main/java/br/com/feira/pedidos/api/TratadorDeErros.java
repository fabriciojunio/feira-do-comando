package br.com.feira.pedidos.api;

import br.com.feira.pedidos.aplicacao.CancelarPedido;
import br.com.feira.plataforma.mensageria.Falhas;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Erros no formato Problem Details, da RFC 9457.
 *
 * O formato e um padrao, e nao um JSON inventado por projeto: qualquer cliente
 * sabe ler {@code type}, {@code title}, {@code status}, {@code detail} e
 * {@code instance}. O campo {@code codigo} entra como extensao, porque a RFC
 * nao tem um campo para codigo de negocio e o cliente precisa de algo estavel
 * para ramificar sem depender do texto.
 */
@RestControllerAdvice
public class TratadorDeErros {

    private static final Logger log = LoggerFactory.getLogger(TratadorDeErros.class);

    private static final String BASE_DOS_TIPOS = "https://feira.dev/erros/";

    private final Clock relogio;

    public TratadorDeErros(Clock relogio) {
        this.relogio = relogio;
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail naoEncontrado(NoSuchElementException e, HttpServletRequest requisicao) {
        return problema(HttpStatus.NOT_FOUND, "nao-encontrado", "Recurso nao encontrado",
                e.getMessage(), "NAO_ENCONTRADO", requisicao);
    }

    @ExceptionHandler(CancelarPedido.CancelamentoNaoPermitido.class)
    public ProblemDetail cancelamentoNaoPermitido(CancelarPedido.CancelamentoNaoPermitido e,
                                                  HttpServletRequest requisicao) {
        var problema = problema(HttpStatus.CONFLICT, "cancelamento-nao-permitido",
                "Pedido nao pode mais ser cancelado", e.getMessage(),
                "CANCELAMENTO_NAO_PERMITIDO", requisicao);
        problema.setProperty("statusDoPedido", e.status());
        return problema;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validacao(MethodArgumentNotValidException e,
                                   HttpServletRequest requisicao) {
        var campos = new LinkedHashMap<String, String>();
        e.getBindingResult().getFieldErrors()
                .forEach(erro -> campos.put(erro.getField(), erro.getDefaultMessage()));

        var problema = problema(HttpStatus.BAD_REQUEST, "dado-invalido",
                "Confira os campos enviados", "Um ou mais campos nao passaram na validacao",
                "DADO_INVALIDO", requisicao);
        problema.setProperty("campos", campos);
        return problema;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail cabecalhoFaltando(MissingRequestHeaderException e,
                                           HttpServletRequest requisicao) {
        return problema(HttpStatus.BAD_REQUEST, "cabecalho-obrigatorio",
                "Cabecalho obrigatorio ausente",
                "Falta o cabecalho " + e.getHeaderName(), "CABECALHO_OBRIGATORIO", requisicao);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, IllegalArgumentException.class, ArithmeticException.class})
    public ProblemDetail requisicaoInvalida(Exception e, HttpServletRequest requisicao) {
        return problema(HttpStatus.BAD_REQUEST, "requisicao-invalida",
                "Nao foi possivel ler a requisicao", e.getMessage(), "REQUISICAO_INVALIDA",
                requisicao);
    }

    /**
     * Duas escritas simultaneas no mesmo pedido. Nao e defeito: e a trava
     * otimista fazendo o trabalho dela. O cliente pode simplesmente repetir.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail conflitoDeVersao(OptimisticLockingFailureException e,
                                          HttpServletRequest requisicao) {
        return problema(HttpStatus.CONFLICT, "conflito-de-versao",
                "O pedido mudou durante a operacao",
                "Outra operacao alterou este pedido ao mesmo tempo. Tente de novo.",
                "CONFLITO_DE_VERSAO", requisicao);
    }

    @ExceptionHandler(Falhas.AguardandoDadoAnterior.class)
    public ProblemDetail aindaProcessando(Falhas.AguardandoDadoAnterior e,
                                          HttpServletRequest requisicao) {
        return problema(HttpStatus.SERVICE_UNAVAILABLE, "ainda-processando",
                "Pedido ainda esta sendo registrado", e.getMessage(), "AINDA_PROCESSANDO",
                requisicao);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail inesperado(Exception e, HttpServletRequest requisicao) {
        var identificador = UUID.randomUUID().toString().substring(0, 8);
        log.error("Erro inesperado [{}] em {} {}", identificador, requisicao.getMethod(),
                requisicao.getRequestURI(), e);

        var problema = problema(HttpStatus.INTERNAL_SERVER_ERROR, "erro-interno",
                "Algo deu errado do nosso lado",
                "Guarde o codigo " + identificador + " se precisar falar com o suporte.",
                "ERRO_INTERNO", requisicao);
        problema.setProperty("identificador", identificador);
        return problema;
    }

    private ProblemDetail problema(HttpStatus status, String tipo, String titulo, String detalhe,
                                   String codigo, HttpServletRequest requisicao) {
        var problema = ProblemDetail.forStatusAndDetail(status, detalhe);
        problema.setType(URI.create(BASE_DOS_TIPOS + tipo));
        problema.setTitle(titulo);
        problema.setInstance(URI.create(requisicao.getRequestURI()));
        problema.setProperty("codigo", codigo);
        problema.setProperty("ocorridoEm", relogio.instant().toString());
        return problema;
    }
}
