class Codigos {
    String gerarTokenDeRecuperacao() {
        // ruleid: random-para-segredo
        var aleatorio = new Random();
        return Long.toHexString(aleatorio.nextLong());
    }

    int codigoDeConfirmacao() {
        // ruleid: random-para-segredo
        return ThreadLocalRandom.current().nextInt(100000, 999999);
    }

    String gerarTokenSeguro() {
        // ok: random-para-segredo
        var seguro = new SecureRandom();
        return Long.toHexString(seguro.nextLong());
    }

    // O falso positivo que a primeira versão dava no AdquirenteSimulado da Feira.
    Motivo sortearMotivo() {
        // ok: random-para-segredo
        int sorteio = ThreadLocalRandom.current().nextInt(100);
        return sorteio < 60 ? Motivo.SALDO : Motivo.CARTAO;
    }
}
