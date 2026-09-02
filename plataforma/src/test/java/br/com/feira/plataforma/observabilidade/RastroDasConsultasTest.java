package br.com.feira.plataforma.observabilidade;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * O trecho de rastro que cada consulta ao banco gera.
 *
 * <p>O que mais importa é o nome do trecho. Painel de rastro agrupa por nome, e
 * um nome que carrega a consulta inteira faz cada execução virar um grupo de
 * uma só, o que apaga a pergunta que a ferramenta existe para responder: qual
 * consulta está lenta no geral.
 */
@DisplayName("Rastro das consultas")
class RastroDasConsultasTest {

    private final RastroDeTeste.Montagem montagem = RastroDeTeste.montar();
    private final RastroDasConsultas rastro = new RastroDasConsultas(montagem.tracer());

    @ParameterizedTest
    @CsvSource({
            "'select e.nome from produto e where e.id = ?', db select produto",
            "'insert into produto (id, nome) values (?, ?)',     db insert produto",
            "'update outbox set publicada_em = ? where id = ?',  db update outbox",
            "'delete from inbox where processado_em < ?',        db delete inbox",
    })
    @DisplayName("o nome do trecho é a operação e a tabela, para o painel poder agrupar")
    void nomeAgrupavel(String comando, String esperado) {
        assertThat(RastroDasConsultas.nomeDoTrecho(comando)).isEqualTo(esperado);
    }

    @ParameterizedTest
    @CsvSource({
            "'SELECT * FROM PRODUTO WHERE ID = ?', db select produto",
            "'  select x from produto  ',               db select produto",
    })
    @DisplayName("caixa alta e espaço em volta não mudam o agrupamento")
    void nomeIgnoraCaixaEEspaco(String comando, String esperado) {
        assertThat(RastroDasConsultas.nomeDoTrecho(comando)).isEqualTo(esperado);
    }

    @Test
    @DisplayName("comando fora dos quatro conhecidos ainda gera trecho, com nome genérico")
    void comandoDesconhecido() {
        assertThat(RastroDasConsultas.nomeDoTrecho("create index idx on tabela (col)"))
                .isEqualTo("db comando");
    }

    @Test
    @DisplayName("select sem from não estoura, devolve marca de desconhecido")
    void selectSemFrom() {
        assertThat(RastroDasConsultas.nomeDoTrecho("select 1")).isEqualTo("db select ?");
    }

    @Test
    @DisplayName("o texto guardado é o comando preparado, sem o valor dos parâmetros")
    void naoGuardaValorDeParametro() {
        String comando = "select * from produto where cpf = ?";

        assertThat(RastroDasConsultas.resumir(comando))
                .as("trecho de rastro sai da aplicação; CPF e e-mail não têm por que ir junto")
                .isEqualTo(comando)
                .contains("?");
    }

    @Test
    @DisplayName("comando enorme de ORM é cortado, para não estourar o trecho")
    void comandoEnormeEhCortado() {
        String enorme = "select " + "coluna_com_nome_longo, ".repeat(200) + "x from tabela";

        String guardado = RastroDasConsultas.resumir(enorme);

        assertThat(guardado).hasSizeLessThan(enorme.length());
        assertThat(guardado).endsWith("...");
    }

    @Test
    @DisplayName("quebra de linha vira espaço, senão o painel mostra uma coluna de texto")
    void quebraDeLinhaVirouEspaco() {
        String comando = "select *\n  from produto\n where id = ?";

        assertThat(RastroDasConsultas.resumir(comando))
                .doesNotContain("\n")
                .isEqualTo("select * from produto where id = ?");
    }

    @Test
    @DisplayName("envolve o DataSource e deixa qualquer outro bean passar intacto")
    void envolveApenasODataSource() {
        DataSource original = mock(DataSource.class);
        Object outro = "qualquer outro bean";

        assertThat(rastro.postProcessAfterInitialization(original, "dataSource"))
                .as("sem envolver, nenhuma consulta aparece no rastro")
                .isNotSameAs(original);
        assertThat(rastro.postProcessAfterInitialization(outro, "outro"))
                .as("envolver o que não é DataSource quebraria o contexto inteiro")
                .isSameAs(outro);
    }

    @Test
    @DisplayName("o que envolve o DataSource continua sendo um DataSource")
    void oEnvolvidoAindaEhDataSource() {
        Object envolvido = rastro.postProcessAfterInitialization(mock(DataSource.class), "dataSource");

        assertThat(envolvido).isInstanceOf(DataSource.class);
    }
}
