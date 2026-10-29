class Config {
    void consumidor(Map<String, Object> props, JsonDeserializer<Object> d) {
        // ruleid: kafka-pacotes-confiaveis-curinga
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        // ruleid: kafka-pacotes-confiaveis-curinga
        d.addTrustedPackages("*");
        // ok: kafka-pacotes-confiaveis-curinga
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "br.com.feira.contratos.eventos");
    }
}
