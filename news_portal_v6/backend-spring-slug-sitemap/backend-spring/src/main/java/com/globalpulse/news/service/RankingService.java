package com.globalpulse.news.service;

import com.globalpulse.news.db.ProcessedArticle;
import com.globalpulse.news.db.ProcessedArticleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Ranking de notícias com score composto.
 *
 * score = recência (50%) + cobertura_portais (30%) + clusters (20%)
 *
 * - Recência:         notícias das últimas 2h = 100pts, decai com tempo
 * - Cobertura:        quantos portais diferentes cobriram o mesmo assunto
 * - Boost de cluster: artigo em cluster grande ganha pontos extras
 *
 * Gera endpoints:
 *  GET /api/db/trending-news   → top notícias rankeadas
 *  GET /api/db/mais-lidas      → alias (mesmos dados, label diferente)
 */
@Service
public class RankingService {

    private static final Logger log = Logger.getLogger(RankingService.class.getName());

    private final ProcessedArticleRepository procRepo;
    private final ClusterService             clusterService;

    public RankingService(ProcessedArticleRepository procRepo, ClusterService clusterService) {
        this.procRepo       = procRepo;
        this.clusterService = clusterService;
    }
    public List<Map<String, Object>> getRanked(int limit) {
        List<ProcessedArticle> articles = procRepo.findAllReady(PageRequest.of(0, 300));
        List<Map<String, Object>> clusters = clusterService.getClusters();

        // Monta mapa de cluster_size por slug de artigo
        Map<String, Integer> clusterSizeBySlug = new HashMap<>();
        for (Map<String, Object> cluster : clusters) {
            int size = (int) cluster.get("size");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> arts = (List<Map<String, Object>>) cluster.get("articles");
            for (Map<String, Object> a : arts) {
                clusterSizeBySlug.put((String) a.get("slug"), size);
            }
        }

        Instant now = Instant.now();

        List<Map<String, Object>> ranked = articles.stream().map(a -> {
            double score = computeScore(a, now, clusterSizeBySlug);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug",        a.getSlug());
            m.put("title",       a.getAiTitle());
            m.put("description", a.getAiDescription());
            m.put("link",        a.getLink());
            m.put("source",      a.getSource());
            m.put("image",       a.getImageUrl());
            m.put("publishedAt", a.getPublishedAt());
            m.put("categories",  a.categoriesArray());
            m.put("category",    a.categoriesArray()[0]);
            m.put("score",       (int) score);
            m.put("cluster_size", clusterSizeBySlug.getOrDefault(a.getSlug(), 1));
            if (a.getEntities() != null) m.put("entities", a.getEntities());
            return m;
        })
        .sorted(Comparator.comparingDouble(
            (Map<String, Object> m) -> (int) m.get("score")).reversed())
        .limit(limit)
        .collect(Collectors.toList());

        log.info("[RANKING] " + ranked.size() + " artigos rankeados");
        return ranked;
    }

    private double computeScore(ProcessedArticle a, Instant now,
                                Map<String, Integer> clusterSizeBySlug) {
        double score = 0;

        // ── Recência (0-50 pts) ───────────────────────────────────
        if (a.getPublishedAt() != null) {
            long hoursOld = ChronoUnit.HOURS.between(a.getPublishedAt(), now);
            if (hoursOld < 0) hoursOld = 0;
            // Decaimento exponencial: 50pts agora, ~25pts após 6h, ~12pts após 12h
            double recencyScore = 50.0 * Math.exp(-0.115 * hoursOld);
            score += recencyScore;
        }

        // ── Cobertura de portais (0-30 pts) ──────────────────────
        // Usa cluster_size como proxy de quantos portais cobriram
        int clusterSize = clusterSizeBySlug.getOrDefault(a.getSlug(), 1);
        double coverageScore = Math.min(30.0, clusterSize * 8.0);
        score += coverageScore;

        // ── Qualidade do conteúdo (0-20 pts) ─────────────────────
        // Título e descrição longos = mais informação = mais relevante
        if (a.getAiTitle() != null && a.getAiTitle().length() > 40) score += 5;
        if (a.getAiDescription() != null && a.getAiDescription().length() > 100) score += 10;
        if (a.getImageUrl() != null && !a.getImageUrl().isBlank()) score += 5;

        return Math.min(100, score); // máximo 100 pts
    }
}
