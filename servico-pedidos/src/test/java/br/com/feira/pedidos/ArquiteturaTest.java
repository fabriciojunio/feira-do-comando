package br.com.feira.pedidos;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Regras de arquitetura verificadas como teste.
 *
 * Diagrama envelhece e ninguem percebe. Estas regras quebram o build no dia em
 * que alguem, com pressa, injeta um repositorio dentro de uma entidade ou faz
 * o dominio importar Spring. E a diferenca entre uma arquitetura escrita no
 * README e uma arquitetura que continua valendo depois de seis meses e quatro
 * pessoas mexendo.
 */
@DisplayName("Arquitetura")
class ArquiteturaTest {

    private static final String RAIZ = "br.com.feira.pedidos";

    private static JavaClasses classes;

    @BeforeAll
    static void importar() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(RAIZ);
    }

    @Test
    @DisplayName("o dominio nao conhece Spring")
    void dominioSemSpring() {
        noClasses()
                .that().resideInAPackage(RAIZ + ".dominio..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                .because("regra de negocio precisa rodar em teste sem subir contexto")
                .check(classes);
    }

    @Test
    @DisplayName("o dominio nao conhece a camada web")
    void dominioSemWeb() {
        noClasses()
                .that().resideInAPackage(RAIZ + ".dominio..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.servlet..", RAIZ + ".api..")
                .because("o dominio nao pode saber que existe HTTP")
                .check(classes);
    }

    @Test
    @DisplayName("o dominio nao conhece Kafka")
    void dominioSemKafka() {
        noClasses()
                .that().resideInAPackage(RAIZ + ".dominio..")
                .should().dependOnClassesThat().resideInAnyPackage("org.apache.kafka..")
                .because("a saga decide; quem publica e a infraestrutura")
                .check(classes);
    }

    @Test
    @DisplayName("controller nao fala com repositorio de escrita direto")
    void controllerNaoEscreveNoBanco() {
        noClasses()
                .that().resideInAPackage(RAIZ + ".api..")
                .should().dependOnClassesThat().resideInAPackage(RAIZ + ".infraestrutura.outbox..")
                .because("efeito colateral passa pela camada de aplicacao, "
                        + "que e quem controla a transacao")
                .check(classes);
    }

    @Test
    @DisplayName("a camada de aplicacao nao conhece HTTP")
    void aplicacaoSemHttp() {
        noClasses()
                .that().resideInAPackage(RAIZ + ".aplicacao..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.servlet..", RAIZ + ".api..")
                .because("caso de uso precisa servir tanto a API quanto a fila")
                .check(classes);
    }

    @Test
    @DisplayName("nada usa Instant.now nem LocalDateTime.now direto")
    void relogioSempreInjetado() {
        noClasses()
                .that().resideOutsideOfPackage(RAIZ + ".infraestrutura.config..")
                .should().callMethod(java.time.Instant.class, "now")
                .orShould().callMethod(java.time.LocalDateTime.class, "now")
                .orShould().callMethod(java.time.LocalDate.class, "now")
                .because("teste que depende da hora do sistema passa hoje e "
                        + "quebra na virada do mes")
                .check(classes);
    }

    @Test
    @DisplayName("ninguem imprime no console")
    void semSystemOut() {
        noClasses()
                .should().accessField(System.class, "out")
                .orShould().accessField(System.class, "err")
                .because("log estruturado, nao println")
                .check(classes);
    }

    @Test
    @DisplayName("nao ha ciclo entre pacotes")
    void semCiclos() {
        SlicesRuleDefinition.slices()
                .matching(RAIZ + ".(*)..")
                .should().beFreeOfCycles()
                .because("ciclo entre pacotes e o comeco do fim de qualquer camada")
                .check(classes);
    }

    @Test
    @DisplayName("entidade JPA tem construtor sem argumento protegido")
    void entidadesTemConstrutorProtegido() {
        classes()
                .that().areAnnotatedWith(jakarta.persistence.Entity.class)
                .should().bePublic()
                .because("o Hibernate precisa instanciar e o Spring precisa enxergar")
                .check(classes);
    }

    @Test
    @DisplayName("repositorio e interface, nunca classe")
    void repositoriosSaoInterfaces() {
        classes()
                .that().haveSimpleNameEndingWith("Repository")
                .should().beInterfaces()
                .because("implementacao de repositorio a mao e sinal de consulta "
                        + "que deveria estar no Spring Data")
                .check(classes);
    }

    /**
     * Hoje nenhuma classe deste servico carrega {@code @Configuration}: a
     * configuracao de mensageria mudou para o modulo compartilhado. A regra
     * fica, com {@code allowEmptyShould}, para valer no dia em que a primeira
     * aparecer. Apagar a regra so porque ela nao tem sujeito agora seria abrir
     * mao dela justamente quando ela voltar a ser necessaria.
     */
    @Test
    @DisplayName("classe de configuracao mora no pacote de infraestrutura")
    void configuracaoNoLugarCerto() {
        classes()
                .that().areAnnotatedWith(org.springframework.context.annotation.Configuration.class)
                .should().resideInAnyPackage(RAIZ + ".infraestrutura..", RAIZ)
                .because("configuracao espalhada e configuracao que ninguem acha")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("a plataforma nao conhece este servico")
    void plataformaNaoConheceOServico() {
        var daPlataforma = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("br.com.feira.plataforma");

        noClasses()
                .that().resideInAPackage("br.com.feira.plataforma..")
                .should().dependOnClassesThat().resideInAnyPackage("br.com.feira.pedidos..")
                .because("modulo compartilhado que conhece um consumidor especifico "
                        + "deixa de ser compartilhado")
                .check(daPlataforma);
    }
}
