package br.com.feira.plataforma.migracao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reprova migração que derruba a versão anterior do código.
 *
 * <h2>O problema</h2>
 * Implantação sem parada roda as duas versões do código ao mesmo tempo, nem que
 * seja por trinta segundos. Se a migração apaga uma coluna que a versão antiga
 * ainda lê, a janela vira erro para quem estiver usando o sistema naquele
 * instante. O mesmo vale para trocar o tipo de uma coluna e para renomear.
 *
 * <h2>O processo que isto obriga</h2>
 * A estratégia é expandir e contrair, em três implantações separadas:
 *
 * <ol>
 *   <li><b>Expandir.</b> A coluna nova entra ao lado da antiga, aceitando nulo.
 *       O código passa a escrever nas duas e a ler da antiga. Nada quebra,
 *       porque nada foi tirado.</li>
 *   <li><b>Migrar.</b> Os dados antigos são copiados para a coluna nova, e o
 *       código passa a ler da nova. A antiga continua lá, ainda escrita, para o
 *       caso de precisar voltar atrás.</li>
 *   <li><b>Contrair.</b> Só depois de a versão anterior não existir mais em
 *       lugar nenhum, a coluna antiga sai. É aqui que o comando destrutivo
 *       entra, e é aqui que ele precisa ser deliberado.</li>
 * </ol>
 *
 * <p>Este teste não impede o passo três. Ele impede que ele aconteça sem
 * alguém ter escrito, no próprio arquivo, que sabe o que está fazendo. A marca
 * é a linha {@code -- contrair:} com o motivo, e ela existe para forçar a
 * pergunta na revisão em vez de na madrugada seguinte.
 */
@DisplayName("Migração sem quebrar a versão anterior")
class MigracaoSemQuebraTest {

    /**
     * A marca que libera um comando destrutivo, com o motivo do lado.
     *
     * <p>Exigir o motivo, e não só a marca, é o que separa uma decisão de um
     * comentário colado para o build passar.
     */
    private static final Pattern MARCA_DE_CONTRACAO =
            // O motivo tem que estar na mesma linha da marca. Com \s o padrão
            // atravessa a quebra e engole o próprio comando como se fosse
            // justificativa, e aí a marca vazia liberaria qualquer coisa.
            Pattern.compile("--[^\\S\\n]*contrair:[^\\S\\n]*\\S+", Pattern.CASE_INSENSITIVE);

    /**
     * Comandos que quebram a versão anterior do código enquanto ela ainda roda.
     *
     * <p>{@code drop table} não está aqui de propósito: tabela inteira sumindo
     * é grande demais para passar despercebido numa revisão, e a lista existe
     * para pegar o que passa.
     */
    private static final List<Pattern> DESTRUTIVOS = List.of(
            Pattern.compile("\\balter\\s+table\\s+\\S+\\s+drop\\s+column\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\balter\\s+table\\s+\\S+\\s+rename\\s+column\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\balter\\s+column\\s+\\S+\\s+type\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\balter\\s+column\\s+\\S+\\s+set\\s+not\\s+null\\b", Pattern.CASE_INSENSITIVE));

    @Test
    @DisplayName("nenhuma migração derruba a versão anterior sem dizer que é de propósito")
    void nenhumaMigracaoDestrutivaSemMarca() throws IOException {
        List<String> problemas = migracoes()
                .flatMap(MigracaoSemQuebraTest::problemasEm)
                .toList();

        assertThat(problemas)
                .as("""
                        Migração destrutiva encontrada. Implantação sem parada roda as duas \
                        versões do código ao mesmo tempo, e apagar ou renomear coluna que a \
                        versão anterior ainda lê quebra quem estiver usando o sistema naquele \
                        instante.

                        Faça em três passos: acrescente a coluna nova ao lado da antiga, migre \
                        os dados e só então apague. Se este arquivo JÁ é o terceiro passo, \
                        escreva no topo dele a linha:

                            -- contrair: <por que a versão anterior não existe mais>
                        """)
                .isEmpty();
    }

    @Test
    @DisplayName("existe pelo menos uma migração para conferir, senão este teste não prova nada")
    void existemMigracoesParaConferir() throws IOException {
        assertThat(migracoes())
                .as("teste que não encontra arquivo nenhum passa sempre, e não vale nada")
                .isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "alter table outbox drop column trace_pai;",
            "ALTER TABLE outbox DROP COLUMN trace_pai;",
            "alter table pedidos rename column status to situacao;",
            "alter table cobrancas alter column valor type numeric(12,2);",
            "alter table itens_de_estoque alter column descricao set not null;",
    })
    @DisplayName("reconhece as formas que quebram, em qualquer caixa")
    void reconheceOsDestrutivos(String comando) {
        assertThat(problemasEm("teste.sql", comando))
                .as("comando que quebra a versão anterior tem que ser pego")
                .isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "alter table outbox add column trace_pai varchar(80);",
            "create index idx_outbox_pendentes on outbox (criada_em);",
            "update outbox set trace_pai = null where trace_pai = '';",
            "alter table outbox alter column ultimo_erro drop not null;",
    })
    @DisplayName("deixa passar o que é compatível com a versão anterior")
    void deixaPassarOCompativel(String comando) {
        assertThat(problemasEm("teste.sql", comando))
                .as("acrescentar coluna, criar índice e afrouxar restrição não quebram ninguém")
                .isEmpty();
    }

    @Test
    @DisplayName("a marca de contração libera, e é o que documenta a decisão")
    void marcaLibera() {
        String comArquivoMarcado = """
                -- contrair: a versao 1.4 saiu de todos os ambientes em 02/09/2026
                alter table outbox drop column trace_pai;
                """;

        assertThat(problemasEm("V9__contrai.sql", comArquivoMarcado)).isEmpty();
    }

    @Test
    @DisplayName("a marca sem motivo não libera, senão vira comentário colado para o build passar")
    void marcaSemMotivoNaoLibera() {
        String semMotivo = """
                -- contrair:
                alter table outbox drop column trace_pai;
                """;

        assertThat(problemasEm("V9__contrai.sql", semMotivo)).isNotEmpty();
    }

    private static Stream<Path> migracoes() throws IOException {
        Path raiz = Path.of("..").toAbsolutePath().normalize();
        try (var caminhos = Files.walk(raiz)) {
            return caminhos
                    .filter(Files::isRegularFile)
                    .filter(caminho -> caminho.toString().endsWith(".sql"))
                    .filter(caminho -> caminho.toString().replace('\\', '/').contains("/resources/db/"))
                    .toList()
                    .stream();
        }
    }

    private static Stream<String> problemasEm(Path arquivo) {
        try {
            return problemasEm(arquivo.getFileName().toString(),
                    Files.readString(arquivo, StandardCharsets.UTF_8)).stream();
        } catch (IOException e) {
            throw new IllegalStateException("Não consegui ler " + arquivo, e);
        }
    }

    private static List<String> problemasEm(String nome, String conteudo) {
        if (MARCA_DE_CONTRACAO.matcher(conteudo).find()) {
            return List.of();
        }
        String semComentario = conteudo.lines()
                .map(linha -> linha.replaceAll("--.*$", ""))
                .reduce("", (a, b) -> a + "\n" + b)
                .toLowerCase(Locale.ROOT);

        return DESTRUTIVOS.stream()
                .filter(destrutivo -> destrutivo.matcher(semComentario).find())
                .map(destrutivo -> nome + " contém " + destrutivo.pattern())
                .toList();
    }
}
