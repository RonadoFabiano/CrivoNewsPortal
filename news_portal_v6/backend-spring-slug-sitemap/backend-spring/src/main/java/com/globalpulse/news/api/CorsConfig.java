package com.globalpulse.news.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig {

    /**
     * Configure as origens permitidas no application.yml:
     *
     *   app:
     *     cors:
     *       allowed-origins: "*"
     *
     * Use "*" para liberar tudo (desenvolvimento).
     * Para produção, liste as origens separadas por vírgula:
     *   "http://192.168.1.50:5173,http://meudominio.com"
     */
    @Value("${app.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                var mapping = registry
                        .addMapping("/api/**")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD")
                        .allowedHeaders("*")
                        .maxAge(3600);

                if ("*".equals(allowedOrigins.trim())) {
                    // Libera qualquer origem (ideal para desenvolvimento em rede local)
                    mapping.allowedOriginPatterns("*");
                } else {
                    // Origens específicas separadas por vírgula
                    String[] origins = allowedOrigins.split(",");
                    for (int i = 0; i < origins.length; i++) {
                        origins[i] = origins[i].trim();
                    }
                    mapping.allowedOrigins(origins);
                }
            }
        };
    }
}
