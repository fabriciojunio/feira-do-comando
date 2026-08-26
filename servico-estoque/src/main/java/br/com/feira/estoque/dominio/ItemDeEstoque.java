package br.com.feira.estoque.dominio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Saldo de um SKU.
 *
 * <h2>Tres numeros, nao um</h2>
 * <ul>
 *   <li>{@code disponivel}: pode ser vendido agora;</li>
 *   <li>{@code reservado}: separado para pedidos em andamento, ainda no
 *       galpao;</li>
 *   <li>{@code vendido}: saiu de vez.</li>
 * </ul>
 *
 * Guardar so um numero e o erro classico: sem separar reservado de disponivel,
 * ou o sistema vende duas vezes a mesma unidade, ou baixa o estoque antes de o
 * pagamento existir e nao consegue devolver o saldo quando o pedido cai.
 *
 * <h2>Como duas vendas simultaneas nao levam a mesma peca</h2>
 * A trava e otimista, com {@link Version}. Dois pedidos que disputam a ultima
 * unidade leem a mesma linha na mesma versao; o primeiro a gravar avanca a
 * versao, e o segundo recebe {@code OptimisticLockingFailureException} do
 * banco em vez de sobrescrever.
 *
 * A alternativa seria {@code SELECT ... FOR UPDATE}. Otimista foi escolhida
 * porque conflito real e raro: a maioria dos pedidos disputa SKUs diferentes,
 * e travar linha em toda reserva serializaria o catalogo inteiro por causa de
 * uma minoria de casos. Onde a disputa e alta, como em lancamento com estoque
 * pequeno, a nova tentativa resolve.
 */
@Entity
@Table(name = "itens_de_estoque")
public class ItemDeEstoque {

    @Id
    @Column(name = "sku", nullable = false, length = 60)
    private String sku;

    @Column(name = "descricao", nullable = false, length = 200)
    private String descricao;

    @Column(name = "disponivel", nullable = false)
    private int disponivel;

    @Column(name = "reservado", nullable = false)
    private int reservado;

    @Column(name = "vendido", nullable = false)
    private int vendido;

    @Column(name = "atualizado_em", nullable = false)
    private Instant atualizadoEm;

    @Version
    @Column(name = "versao", nullable = false)
    private long versao;

    protected ItemDeEstoque() {
        // exigido pelo JPA
    }

    public ItemDeEstoque(String sku, String descricao, int disponivel, Instant agora) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku e obrigatorio");
        }
        if (disponivel < 0) {
            throw new IllegalArgumentException("saldo inicial nao pode ser negativo");
        }
        this.sku = sku;
        this.descricao = descricao;
        this.disponivel = disponivel;
        this.reservado = 0;
        this.vendido = 0;
        this.atualizadoEm = agora;
    }

    /**
     * Separa unidades para um pedido.
     *
     * @return verdadeiro se conseguiu. Falso, e nao excecao, porque falta de
     *         saldo e resposta normal do negocio: o servico precisa avisar o
     *         pedido, nao registrar um erro.
     */
    public boolean reservar(int quantidade, Instant agora) {
        exigirQuantidadePositiva(quantidade);
        if (disponivel < quantidade) {
            return false;
        }
        this.disponivel -= quantidade;
        this.reservado += quantidade;
        this.atualizadoEm = agora;
        return true;
    }

    /**
     * Devolve unidades reservadas ao saldo disponivel. E a compensacao da
     * saga, e precisa ser tolerante: chamar duas vezes nao pode inventar
     * mercadoria.
     */
    public void liberar(int quantidade, Instant agora) {
        exigirQuantidadePositiva(quantidade);
        int devolvido = Math.min(quantidade, reservado);
        this.reservado -= devolvido;
        this.disponivel += devolvido;
        this.atualizadoEm = agora;
    }

    /** Confirma a saida: o que estava reservado vira vendido. */
    public void baixar(int quantidade, Instant agora) {
        exigirQuantidadePositiva(quantidade);
        int baixado = Math.min(quantidade, reservado);
        this.reservado -= baixado;
        this.vendido += baixado;
        this.atualizadoEm = agora;
    }

    public void repor(int quantidade, Instant agora) {
        exigirQuantidadePositiva(quantidade);
        this.disponivel += quantidade;
        this.atualizadoEm = agora;
    }

    /**
     * O que existe fisicamente no galpao. Reservado ainda esta la; vendido nao.
     * Serve de checagem de sanidade em inventario.
     */
    public int emMaos() {
        return disponivel + reservado;
    }

    private static void exigirQuantidadePositiva(int quantidade) {
        if (quantidade < 1) {
            throw new IllegalArgumentException("quantidade precisa ser positiva");
        }
    }

    public String sku() {
        return sku;
    }

    public String descricao() {
        return descricao;
    }

    public int disponivel() {
        return disponivel;
    }

    public int reservado() {
        return reservado;
    }

    public int vendido() {
        return vendido;
    }

    public Instant atualizadoEm() {
        return atualizadoEm;
    }

    public long versao() {
        return versao;
    }
}
