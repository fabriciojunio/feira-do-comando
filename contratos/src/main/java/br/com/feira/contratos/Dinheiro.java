package br.com.feira.contratos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * Valor monetario em centavos.
 *
 * Nao existe {@code double} em dinheiro neste projeto. O motivo classico:
 * 0.1 + 0.2 nao da 0.3 em ponto flutuante binario, e um carrinho com dez itens
 * fecha com um centavo de diferenca que ninguem consegue explicar depois.
 */
public record Dinheiro(long centavos) implements Comparable<Dinheiro> {

    public static final Dinheiro ZERO = new Dinheiro(0);

    public Dinheiro {
        if (centavos < 0) {
            throw new IllegalArgumentException("valor monetario nao pode ser negativo");
        }
    }

    public static Dinheiro de(String reais) {
        var decimal = new BigDecimal(reais).setScale(2, RoundingMode.HALF_UP);
        return new Dinheiro(decimal.movePointRight(2).longValueExact());
    }

    public Dinheiro mais(Dinheiro outro) {
        return new Dinheiro(centavos + outro.centavos);
    }

    public Dinheiro vezes(int quantidade) {
        if (quantidade < 0) {
            throw new IllegalArgumentException("quantidade nao pode ser negativa");
        }
        return new Dinheiro(Math.multiplyExact(centavos, quantidade));
    }

    public BigDecimal emReais() {
        return BigDecimal.valueOf(centavos, 2);
    }

    public String formatado() {
        return String.format(Locale.of("pt", "BR"), "R$ %,.2f", emReais());
    }

    @Override
    public int compareTo(Dinheiro outro) {
        return Long.compare(centavos, outro.centavos);
    }
}
