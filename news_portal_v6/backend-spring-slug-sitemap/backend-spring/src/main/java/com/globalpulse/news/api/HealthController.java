package com.globalpulse.news.api;

import com.globalpulse.news.db.ProcessedArticleRepository;
import com.globalpulse.news.db.RawArticleRepository;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GET /api/db/health
 *
 * Painel completo do sistema — responde em <2s.
 * Mostra tudo de uma vez: banco, fila de IA, entidades, cache, Ollama.
 *
 * {
 *   "status": "OK",
 *   "timestamp": "...",
 *   "database": { "raw_total":100, "pending":5, "done":90, "failed":5, "processed":90 },
 *   "entities": { "total":90, "done":40, "failed":2, "pending":48 },
 *   "cache":    { "news-feed":{...}, "news-article":{...}, "sitemap":{...} },
 *   "ollama":   { "reachable":true, "url":"http://localhost:11434" },
 *   "workers":  { "ingestion":"running", "ai_summary":"running", "entity":"running" }
 * }
 */
@RestController
public class HealthController {

    private final RawArticleRepository       rawRepo;
    private final ProcessedArticleRepository procRepo;
    private final CacheManager               cacheManager;

    @org.springframework.beans.factory.annotation.Value("${ai.ollama.baseUrl:http://localhost:11434}")
    private String ollamaUrl;

    public HealthController(RawArticleRepository rawRepo,
                            ProcessedArticleRepository procRepo,
                            CacheManager cacheManager) {
        this.rawRepo      = rawRepo;
        this.procRepo     = procRepo;
        this.cacheManager = cacheManager;
    }

    @GetMapping("/api/db/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status",    "OK");
        result.put("timestamp", Instant.now().toString());

        // ── Banco de dados ────────────────────────────────────────
        Map<String, Object> db = new LinkedHashMap<>();
        try {
            long rawTotal  = rawRepo.count();
            long pending   = rawRepo.countByAiStatus("PENDING");
            long done      = rawRepo.countByAiStatus("DONE");
            long failed    = rawRepo.countByAiStatus("FAILED");
            long processed = procRepo.count();

            db.put("raw_total",  rawTotal);
            db.put("pending",    pending);
            db.put("done",       done);
            db.put("failed",     failed);
            db.put("processed",  processed);
            db.put("queue_size", pending);
            db.put("ok", true);
        } catch (Exception e) {
            db.put("ok",    false);
            db.put("error", e.getMessage());
            result.put("status", "DEGRADED");
        }
        result.put("database", db);

        // ── Entidades ─────────────────────────────────────────────
        Map<String, Object> entities = new LinkedHashMap<>();
        try {
            long total   = procRepo.count();
            long entDone = procRepo.countByEntityStatus("DONE");
            long entFail = procRepo.countByEntityStatus("FAILED");
            long entPend = total - entDone - entFail;

            entities.put("total",   total);
            entities.put("done",    entDone);
            entities.put("failed",  entFail);
            entities.put("pending", Math.max(0, entPend));
            entities.put("progress_pct",
                total > 0 ? String.format("%.0f%%", (entDone * 100.0 / total)) : "0%");
        } catch (Exception e) {
            entities.put("error", e.getMessage());
        }
        result.put("entities", entities);

        // ── Cache stats ───────────────────────────────────────────
        Map<String, Object> caches = new LinkedHashMap<>();
        for (String name : new String[]{"news-feed", "news-article", "sitemap"}) {
            var cache = cacheManager.getCache(name);
            if (cache != null) {
                var nc = (com.github.benmanes.caffeine.cache.Cache<?,?>) cache.getNativeCache();
                var s  = nc.stats();
                Map<String, Object> cs = new LinkedHashMap<>();
                cs.put("size",     nc.estimatedSize());
                cs.put("hits",     s.hitCount());
                cs.put("misses",   s.missCount());
                cs.put("hit_rate", String.format("%.1f%%", s.hitRate() * 100));
                caches.put(name, cs);
            }
        }
        result.put("cache", caches);

        // ── Ollama ────────────────────────────────────────────────
        Map<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("url", ollamaUrl);
        try {
            HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(ollamaUrl.endsWith("/") ? ollamaUrl : ollamaUrl + "/"))
                .timeout(Duration.ofSeconds(3))
                .GET().build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            ollama.put("reachable", res.statusCode() < 500);
            ollama.put("status_code", res.statusCode());
        } catch (Exception e) {
            ollama.put("reachable", false);
            ollama.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            result.put("status", "DEGRADED");
        }
        result.put("ollama", ollama);

        // ── Workers ───────────────────────────────────────────────
        Map<String, Object> workers = new LinkedHashMap<>();
        workers.put("ingestion",  "scheduled every 5min");
        workers.put("ai_summary", "scheduled every 15s");
        workers.put("entity",     "scheduled every 25s");
        result.put("workers", workers);

        // ── Dicas de diagnóstico ──────────────────────────────────
        Map<String, Object> hints = new LinkedHashMap<>();
        long pending = (long) db.getOrDefault("pending", 0L);
        long entPend = (long) entities.getOrDefault("pending", 0L);
        if (pending > 0)  hints.put("ai_queue",     pending + " artigos aguardando Ollama");
        if (entPend > 0)  hints.put("entity_queue", entPend + " artigos aguardando extração de entidades");
        if (!hints.isEmpty()) result.put("hints", hints);

        return ResponseEntity.ok(result);
    }
}
