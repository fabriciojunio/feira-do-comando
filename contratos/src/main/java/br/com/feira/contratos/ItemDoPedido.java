package br.com.feira.contratos;

/**
 * Uma linha do pedido, do jeito que trafega entre os servicos.
 *
 * O preco unitario vai junto e nao e consultado de novo do outro lado. Preco
 * muda; o que foi cobrado do cliente naquele instante nao pode mudar depois. E
 * a mesma razao pela qual nota fiscal guarda valor, e nao um ponteiro para a
 * tabela de precos.
 */
public record ItemDoPedido(String sku, String descricao, int quantidade, Dinheiro precoUnitario) {

    public ItemDoPedido {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku e obrigatorio");
        }
        if (quantidade < 1) {
            throw new IllegalArgumentException("quantidade minima de um item e 1");
        }
        if (precoUnitario == null) {
            throw new IllegalArgumentException("preco unitario e obrigatorio");
        }
    }

    public Dinheiro subtotal() {
        return precoUnitario.vezes(quantidade);
    }
}
