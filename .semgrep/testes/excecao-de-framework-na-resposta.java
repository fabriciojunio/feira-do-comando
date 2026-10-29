class Tratador {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail ler(HttpMessageNotReadableException e) {
        // ruleid: excecao-de-framework-na-resposta
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, ArithmeticException.class})
    ProblemDetail generico(Exception e) {
        // ruleid: excecao-de-framework-na-resposta
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(PedidoNaoEncontrado.class)
    ProblemDetail dominio(PedidoNaoEncontrado e) {
        // ok: excecao-de-framework-na-resposta
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    ProblemDetail foraDeTratador(Exception e) {
        // ok: excecao-de-framework-na-resposta
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    // O verdadeiro positivo do brecha: mensagem dentro do corpo da resposta, não no log.
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> qualquer(Exception e) {
        // ruleid: excecao-de-framework-na-resposta
        return ResponseEntity.status(500).body(Map.of("mensagem", String.valueOf(e.getMessage())));
    }

    // A correção da Feira: o detalhe vai para o log e o cliente recebe texto fixo.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail corpoIlegivel(HttpMessageNotReadableException e) {
        // ok: excecao-de-framework-na-resposta
        log.info("Requisicao ilegivel: {}", semQuebraDeLinha(e.getMessage()));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "O corpo enviado nao e um JSON valido.");
    }

    // O falso positivo que a primeira versão dava na Vitrine: só o tipo de domínio leva a mensagem.
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ProblemDetail protegido(Exception excecao) {
        String detalhe = excecao instanceof IllegalArgumentException
                // ok: excecao-de-framework-na-resposta
                ? excecao.getMessage()
                : "Não foi possível ler os dados enviados.";
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detalhe);
    }
}
