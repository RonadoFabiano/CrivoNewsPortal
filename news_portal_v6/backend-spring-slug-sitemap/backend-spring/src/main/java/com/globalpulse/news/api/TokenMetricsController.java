package com.globalpulse.news.api;

import com.globalpulse.news.ai.GroqRateLimiter;
import com.globalpulse.news.db.ProcessedArticleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Endpoints administrativos para o dashboard de sistema.
 */
@RestController
@RequestMapping("/api/admin")
public class TokenMetricsController {

    private final GroqRateLimiter rateLimiter;
    private final ProcessedArticleRepository processedArticleRepository;

    public TokenMetricsController(
        GroqRateLimiter rateLimiter,
        ProcessedArticleRepository processedArticleRepository
    ) {
        this.rateLimiter = rateLimiter;
        this.processedArticleRepository = processedArticleRepository;
    }

    /**
     * GET /api/admin/token-metrics
     * Retorna consumo atual + historico de ciclos por key.
     */
    @GetMapping("/token-metrics")
    public ResponseEntity<Map<String, Object>> tokenMetrics() {
        return ResponseEntity.ok(rateLimiter.dashboardStatus());
    }

    /**
     * GET /api/admin/source-stats
     * Retorna ranking por portal e distribuicao por categoria.
     */
    @GetMapping("/source-stats")
    public ResponseEntity<Map<String, Object>> sourceStats() {
        List<Object[]> rows = processedArticleRepository.findSourceCategoryRows();

        Map<String, SourceAccumulator> bySource = new LinkedHashMap<>();
        Map<String, Integer> overallCategories = new LinkedHashMap<>();

        for (Object[] row : rows) {
            String source = normalizeLabel((String) row[0], "Sem fonte");
            String categoriesCsv = row[1] instanceof String ? (String) row[1] : null;

            SourceAccumulator acc = bySource.computeIfAbsent(source, SourceAccumulator::new);
            acc.total++;

            List<String> categories = parseCategories(categoriesCsv);
            String primary = categories.get(0);
            acc.primaryCategoryCounts.merge(primary, 1, Integer::sum);
            overallCategories.merge(primary, 1, Integer::sum);

            for (String category : categories) {
                acc.categoryCounts.merge(category, 1, Integer::sum);
            }
        }

        List<Map<String, Object>> sources = bySource.values().stream()
            .sorted(Comparator.comparingInt(SourceAccumulator::total).reversed()
                .thenComparing(SourceAccumulator::source))
            .map(SourceAccumulator::toMap)
            .toList();

        List<Map<String, Object>> categoryRanking = overallCategories.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry::getKey))
            .map(entry -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("category", entry.getKey());
                item.put("count", entry.getValue());
                return item;
            })
            .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalArticles", rows.size());
        result.put("totalSources", bySource.size());
        result.put("sources", sources);
        result.put("categoryTotals", categoryRanking);
        return ResponseEntity.ok(result);
    }

    private static List<String> parseCategories(String categoriesCsv) {
        if (categoriesCsv == null || categoriesCsv.isBlank()) {
            return List.of("Geral");
        }

        List<String> categories = new ArrayList<>();
        for (String raw : categoriesCsv.split(",")) {
            String normalized = normalizeLabel(raw, null);
            if (normalized != null && !categories.contains(normalized)) {
                categories.add(normalized);
            }
        }
        return categories.isEmpty() ? List.of("Geral") : categories;
    }

    private static String normalizeLabel(String value, String fallback) {
        if (value == null) return fallback;
        String normalized = value.trim();
        if (normalized.isEmpty()) return fallback;
        return normalized;
    }

    private static final class SourceAccumulator {
        private final String source;
        private int total = 0;
        private final Map<String, Integer> categoryCounts = new LinkedHashMap<>();
        private final Map<String, Integer> primaryCategoryCounts = new LinkedHashMap<>();

        private SourceAccumulator(String source) {
            this.source = source;
        }

        private int total() {
            return total;
        }

        private String source() {
            return source.toLowerCase(Locale.ROOT);
        }

        private Map<String, Object> toMap() {
            List<Map<String, Object>> categories = categoryCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                    .thenComparing(Map.Entry::getKey))
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("category", entry.getKey());
                    item.put("count", entry.getValue());
                    return item;
                })
                .toList();

            String dominantCategory = primaryCategoryCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                    .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("Geral");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("source", source);
            result.put("total", total);
            result.put("dominantCategory", dominantCategory);
            result.put("categories", categories);
            return result;
        }
    }
}
