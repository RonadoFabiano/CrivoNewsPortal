package com.globalpulse.news.api;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Cache em memória com Caffeine.
 *
 * Caches configurados:
 *  - "news-feed"    → feed principal  — TTL 5 min, max 50 entradas
 *  - "news-article" → artigo por slug — TTL 10 min, max 500 entradas
 *  - "sitemap"      → sitemap.xml     — TTL 60 min, max 1 entrada
 *
 * O AiSummaryWorker invalida "news-feed" e "sitemap" após
 * processar cada artigo, garantindo que novos artigos apareçam
 * no feed sem esperar o TTL expirar.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        // Feed principal — 5 min TTL, max 50 chaves (category+limit combos)
        manager.registerCustomCache("news-feed",
            Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(100)
                .recordStats()
                .build());

        // Artigo individual — 10 min TTL, max 500 slugs
        manager.registerCustomCache("news-article",
            Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(500)
                .recordStats()
                .build());

        // Sitemap — 60 min TTL (geração é cara)
        manager.registerCustomCache("sitemap",
            Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.MINUTES)
                .maximumSize(1)
                .recordStats()
                .build());

        return manager;
    }
}
