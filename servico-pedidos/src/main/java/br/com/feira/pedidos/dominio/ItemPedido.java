package br.com.feira.pedidos.dominio;

import br.com.feira.contratos.Dinheiro;
import br.com.feira.contratos.ItemDoPedido;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Item gravado junto do pedido.
 *
 * O preco unitario e copiado no momento da compra e nunca mais consultado da
 * tabela de produtos. Promocao acaba, preco sobe; o que o cliente pagou fica
 * congelado. Guardar so o SKU e recalcular depois e como se descobre, na
 * auditoria, que o relatorio de faturamento de marco mudou sozinho.
 */
@Embeddable
public class ItemPedido {

    @Column(name = "sku", nullable = false, length = 60)
    private String sku;

    @Column(name = "descricao", nullable = false, length = 200)
    private String descricao;

    @Column(name = "quantidade", nullable = false)
    private int quantidade;

    @Column(name = "preco_unitario_centavos", nullable = false)
    private long precoUnitarioCentavos;

    protected ItemPedido() {
        // exigido pelo JPA
    }

    public ItemPedido(String sku, String descricao, int quantidade, Dinheiro precoUnitario) {
        this.sku = sku;
        this.descricao = descricao;
        this.quantidade = quantidade;
        this.precoUnitarioCentavos = precoUnitario.centavos();
    }

    public static ItemPedido de(ItemDoPedido item) {
        return new ItemPedido(item.sku(), item.descricao(), item.quantidade(), item.precoUnitario());
    }

    public ItemDoPedido paraContrato() {
        return new ItemDoPedido(sku, descricao, quantidade, precoUnitario());
    }

    public Dinheiro precoUnitario() {
        return new Dinheiro(precoUnitarioCentavos);
    }

    public Dinheiro subtotal() {
        return precoUnitario().vezes(quantidade);
    }

    public String sku() {
        return sku;
    }

    public String descricao() {
        return descricao;
    }

    public int quantidade() {
        return quantidade;
    }
}
