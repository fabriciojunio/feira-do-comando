class Cors {
    void configurar(CorsRegistry registro, CorsConfiguration config) {
        // ruleid: cors-qualquer-origem
        registro.addMapping("/**").allowedOrigins("*");
        // ruleid: cors-qualquer-origem
        config.addAllowedOrigin("*");
        // ok: cors-qualquer-origem
        config.setAllowedOrigins(List.of("https://feira.exemplo.com.br"));
    }

    // ruleid: cors-qualquer-origem
    @CrossOrigin
    String aberto() { return ""; }

    // ok: cors-qualquer-origem
    @CrossOrigin(origins = "https://feira.exemplo.com.br")
    String fechado() { return ""; }
}
