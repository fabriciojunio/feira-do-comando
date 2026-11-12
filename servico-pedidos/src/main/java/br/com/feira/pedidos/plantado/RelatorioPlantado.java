package br.com.feira.pedidos.plantado;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * FALHAS PLANTADAS DE PROPÓSITO. Esta classe só existe na branch prova-dos-portoes, para mostrar
 * que cada portão da esteira barra o que deveria. Nunca vai para homologação nem produção.
 *
 * <ol>
 *   <li>SQL montado por concatenação: Semgrep (regra própria) e CodeQL.</li>
 *   <li>Mensagem de exceção genérica na resposta: Semgrep (regra própria).</li>
 *   <li>Senha escrita no código: gitleaks.</li>
 * </ol>
 * A quarta falha é a dependência commons-text 1.9 (Text4Shell) no pom, para o OSV-Scanner.
 */
@RestController
public class RelatorioPlantado {

    private static final String DB_PASSWORD = "R7v!q2Lp9#Xm4sTz8Wk3nB6yH1cF5dJ0";

    @PersistenceContext
    private EntityManager em;

    @GetMapping("/plantado/relatorio")
    public List<?> relatorio(@RequestParam String cliente) {
        return em.createNativeQuery("select * from pedido where cliente_id = '" + cliente + "'").getResultList();
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail erro(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage() + DB_PASSWORD.length());
    }
}
