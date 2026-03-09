package com.globalpulse.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalpulse.news.ai.NarrativeAnalyzer;
import com.globalpulse.news.ai.NarrativeAnalyzer.ConnectionAnalysis;
import com.globalpulse.news.ai.NarrativeAnalyzer.SpreadPath;
import com.globalpulse.news.ai.NarrativeAnalyzer.SpreadNode;
import com.globalpulse.news.db.ProcessedArticle;
import com.globalpulse.news.db.ProcessedArticleRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * MapNarrativeWorker — roda a cada 30 minutos e:
 *
 * 1. Pega as top conexões (pares de países com alta coocorrência)
 * 2. Para cada par, chama NarrativeAnalyzer.analyzeConnection()
 *    → classifica o tipo de relação (conflito, diplomacia, etc.)
 * 3. Para os top 5 países, gera o spread path com analyzeSpread()
 * 4. Tudo fica em cache em memória com TTL de 35 minutos
 *
 * O endpoint /api/map/narrative serve este cache.
 * Se o cache estiver vazio (primeiro boot), roda imediatamente.
 */
@Component
public class MapNarrativeWorker {

    private static final Logger log = Logger.getLogger(MapNarrativeWorker.class.getName());

    // ── Cache em memória ──────────────────────────────────────────────────────
    // Chave: "pairA|pairB" (alfabética) → análise da conexão
    private final Map<String, ConnectionAnalysis> connectionCache = new ConcurrentHashMap<>();
    // Chave: "country" → spread path
    private final Map<String, SpreadPath> spreadCache = new ConcurrentHashMap<>();
    // Quando o cache foi gerado pela última vez
    private volatile Instant lastRun = null;
    private volatile int lastArticleCount = 0;

    private final ProcessedArticleRepository procRepo;
    private final NarrativeAnalyzer          analyzer;
    private final ObjectMapper               mapper;

    public MapNarrativeWorker(ProcessedArticleRepository procRepo,
                               NarrativeAnalyzer analyzer,
                               ObjectMapper mapper) {
        this.procRepo = procRepo;
        this.analyzer = analyzer;
        this.mapper   = mapper;
    }

    // Roda 2min após boot (deixa AiSummaryWorker iniciar primeiro), depois a cada 30min
    @Scheduled(initialDelay = 120_000, fixedDelay = 1_800_000)
    public void run() {
        try { analyzeNow(); }
        catch (Exception e) {
            log.warning("[MAP-NARRATIVE] Falhou: " + e.getMessage());
        }
    }

    /**
     * Execução manual ou agendada — pode ser chamada pelo endpoint /api/map/narrative/refresh
     */
    public void analyzeNow() {
        Instant since = Instant.now().minus(12, ChronoUnit.HOURS);
        List<ProcessedArticle> articles = procRepo.findRecentAll(since).stream()
            .filter(a -> "DONE".equals(a.getEntityStatus()))
            .sorted(Comparator.comparing(a -> a.getPublishedAt() != null ? a.getPublishedAt() : Instant.EPOCH))
            .collect(Collectors.toList());

        if (articles.isEmpty()) {
            log.info("[MAP-NARRATIVE] Nenhum artigo com entidades — pulando");
            return;
        }

        // Evita reprocessar se os dados não mudaram
        if (articles.size() == lastArticleCount && lastRun != null
                && Instant.now().isBefore(lastRun.plus(25, ChronoUnit.MINUTES))) {
            log.info("[MAP-NARRATIVE] Cache ainda válido — pulando");
            return;
        }

        log.info("[MAP-NARRATIVE] Iniciando análise com " + articles.size() + " artigos");

        // ── ETAPA 1: Conta coocorrências ──────────────────────────────────────
        Map<String, Integer> pairCount    = new LinkedHashMap<>();
        Map<String, List<String>> pairHeadlines = new HashMap<>();
        Map<String, CountryData> countryData = new HashMap<>();

        for (ProcessedArticle art : articles) {
            try {
                JsonNode node = mapper.readTree(art.getEntities());
                List<String> countries = new ArrayList<>();
                if (node.path("countries").isArray()) {
                    for (JsonNode c : node.path("countries")) {
                        String name = c.asText("").trim();
                        if (!name.isBlank()) countries.add(name);
                    }
                }

                // Acumula dados por país para spread path
                for (String country : countries) {
                    countryData.computeIfAbsent(country, k -> new CountryData()).add(art);
                }

                // Conta coocorrências de pares
                for (int i = 0; i < countries.size(); i++) {
                    for (int j = i + 1; j < countries.size(); j++) {
                        String a = countries.get(i), b = countries.get(j);
                        String key = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
                        pairCount.merge(key, 1, Integer::sum);
                        pairHeadlines.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(art.getAiTitle() != null ? art.getAiTitle() : "");
                    }
                }
            } catch (Exception ignored) {}
        }

        // ── ETAPA 2: Analisa top 8 conexões mais fortes ───────────────────────
        pairCount.entrySet().stream()
            .filter(e -> e.getValue() >= 3) // só pares com ≥ 3 artigos em comum
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(8)
            .forEach(e -> {
                String[] parts  = e.getKey().split("\\|", 2);
                if (parts.length != 2) return;
                String a = parts[0], b = parts[1];
                List<String> headlines = pairHeadlines.getOrDefault(e.getKey(), List.of());

                log.info("[MAP-NARRATIVE] Analisando par: " + a + " ↔ " + b
                        + " (" + e.getValue() + " artigos)");

                ConnectionAnalysis analysis = analyzer.analyzeConnection(a, b, headlines);
                connectionCache.put(e.getKey(), analysis);
            });

        // ── ETAPA 3: Gera spread path para top 4 países ───────────────────────
        countryData.entrySet().stream()
            .sorted(Comparator.comparingInt((Map.Entry<String, CountryData> e) ->
                e.getValue().articles.size()).reversed())
            .limit(4)
            .forEach(e -> {
                String country = e.getKey();
                List<Map<String, Object>> artMaps = e.getValue().articles.stream()
                    .map(art -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("title", art.getAiTitle() != null ? art.getAiTitle() : "");
                        m.put("publishedAt", art.getPublishedAt() != null
                            ? art.getPublishedAt().toString() : "");
                        // Países mencionados no artigo
                        try {
                            JsonNode node = mapper.readTree(art.getEntities());
                            List<String> cs = new ArrayList<>();
                            node.path("countries").forEach(c -> cs.add(c.asText()));
                            m.put("countries", String.join(", ", cs));
                        } catch (Exception ignored) {}
                        return m;
                    })
                    .collect(Collectors.toList());

                log.info("[MAP-NARRATIVE] SpreadPath para: " + country);
                SpreadPath spread = analyzer.analyzeSpread(artMaps, country);
                spreadCache.put(country, spread);
            });

        lastRun = Instant.now();
        lastArticleCount = articles.size();
        log.info("[MAP-NARRATIVE] Concluído — "
            + connectionCache.size() + " conexões, "
            + spreadCache.size() + " spread paths");
    }

    // ── Acesso ao cache ────────────────────────────────────────────────────────

    public Map<String, Object> getCachedNarratives() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Serializa conexões
        List<Map<String, Object>> connections = new ArrayList<>();
        connectionCache.forEach((key, ca) -> {
            if (ca.confidence() < 30) return; // descarta análises pouco confiantes
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source",    ca.sourceCountry());
            m.put("target",    ca.targetCountry());
            m.put("type",      ca.relationType().name().toLowerCase());
            m.put("label",     ca.relationLabel());
            m.put("summary",   ca.narrativeSummary());
            m.put("confidence", ca.confidence());
            connections.add(m);
        });
        connections.sort(Comparator.comparingInt(
            (Map<String, Object> m) -> (int) m.get("confidence")).reversed());

        // Serializa spread paths
        Map<String, Object> spreads = new LinkedHashMap<>();
        spreadCache.forEach((country, sp) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("summary", sp.narrativeSummary());
            m.put("nodes", sp.nodes().stream().map(n -> {
                Map<String, Object> nm = new LinkedHashMap<>();
                nm.put("name",    n.name());
                nm.put("type",    n.type());
                nm.put("role",    n.role());
                nm.put("enteredAt", n.enteredAt());
                return nm;
            }).collect(Collectors.toList()));
            spreads.put(country, m);
        });

        result.put("connections", connections);
        result.put("spreadPaths", spreads);
        result.put("generatedAt", lastRun != null ? lastRun.toString() : null);
        result.put("ready", lastRun != null);
        return result;
    }

    public boolean isReady() { return lastRun != null; }

    // ── Auxiliar ───────────────────────────────────────────────────────────────

    private static class CountryData {
        List<ProcessedArticle> articles = new ArrayList<>();
        void add(ProcessedArticle a) {
            if (articles.size() < 20) articles.add(a);
        }
    }
}
