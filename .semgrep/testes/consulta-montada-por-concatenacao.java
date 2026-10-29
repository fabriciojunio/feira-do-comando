class Repositorio {
    void buscar(EntityManager em, JdbcTemplate jdbc, String nome) {
        // ruleid: consulta-montada-por-concatenacao
        em.createQuery("select p from Pedido p where p.cliente = '" + nome + "'");
        // ruleid: consulta-montada-por-concatenacao
        em.createNativeQuery(String.format("select * from pedido where cliente = '%s'", nome));
        // ruleid: consulta-montada-por-concatenacao
        jdbc.queryForList("select * from pedido where cliente = '" + nome + "'");
        // ok: consulta-montada-por-concatenacao
        em.createQuery("select p from Pedido p where p.cliente = :nome").setParameter("nome", nome);
        // ok: consulta-montada-por-concatenacao
        jdbc.queryForList("select * from pedido where cliente = ?", nome);
        // ok: consulta-montada-por-concatenacao
        log.info("buscando " + nome);
    }
}
