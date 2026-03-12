package com.globalpulse.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalpulse.news.ai.GroqEntityExtractor;
import com.globalpulse.news.config.AppRuntimeProperties;
import com.globalpulse.news.db.ProcessedArticle;
import com.globalpulse.news.db.ProcessedArticleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

@Component
public class EntityWorker {

    private static final Logger log = Logger.getLogger(EntityWorker.class.getName());

    private final ProcessedArticleRepository procRepo;
    private final GroqEntityExtractor extractor;
    private final ObjectMapper mapper;
    private final AppRuntimeProperties runtimeProperties;

    public EntityWorker(ProcessedArticleRepository procRepo,
                        GroqEntityExtractor extractor,
                        ObjectMapper mapper,
                        AppRuntimeProperties runtimeProperties) {
        this.procRepo = procRepo;
        this.extractor = extractor;
        this.mapper = mapper;
        this.runtimeProperties = runtimeProperties;
    }

    @Scheduled(
            initialDelayString = "${app.ai.entity-worker.initial-delay-ms:75000}",
            fixedDelayString = "${app.ai.entity-worker.delay-ms:20000}"
    )
    public void runOne() {
        if (runtimeProperties.getLab().isDisableWorkers()) return;
        if (!runtimeProperties.getAi().getEntityWorker().isEnabled()) return;

        int batchSize = Math.max(1, runtimeProperties.getAi().getEntityWorker().getBatchSize());
        List<ProcessedArticle> queue = procRepo.findPendingEntities(PageRequest.of(0, batchSize));
        if (queue.isEmpty()) return;

        for (ProcessedArticle article : queue) {
            processOne(article);
        }
    }

    private void processOne(ProcessedArticle article) {
        log.info("[ENTITY-WORKER] Extraindo: \"" + safe(article.getAiTitle()) + "\"");

        String result = extractor.extract(article.getAiTitle(), article.getAiDescription());
        if (result == null) {
            article.setEntityStatus("FAILED");
            article.setEntities("{\"countries\":[],\"people\":[],\"organizations\":[],"
                    + "\"topics\":[],\"location\":{\"state\":null,\"city\":null},"
                    + "\"scope\":\"nacional\",\"tone\":\"neutro\","
                    + "\"hasVictims\":false,\"victimCount\":-1,\"keyFact\":\"\"}");
            procRepo.save(article);
            log.warning("[ENTITY-WORKER] FAILED: " + safe(article.getAiTitle()));
            return;
        }

        article.setEntities(result);
        article.setAiTags(buildTags(result, article.getAiCategories()));
        article.setEntityStatus("DONE");

        try {
            JsonNode node = mapper.readTree(result);
            article.setScope(node.path("scope").asText("nacional"));
            article.setTone(node.path("tone").asText("neutro"));
            article.setKeyFact(node.path("keyFact").asText(""));
            article.setHasVictims(node.path("hasVictims").asBoolean(false));
            article.setVictimCount(node.path("victimCount").asInt(-1));
            JsonNode loc = node.path("location");
            String st = loc.path("state").asText("").trim();
            String ct = loc.path("city").asText("").trim();
            article.setLocationState(st.equals("null") || st.isBlank() ? null : st);
            article.setLocationCity(ct.equals("null") || ct.isBlank() ? null : ct);
        } catch (Exception e) {
            log.warning("[ENTITY-WORKER] Falhou ao salvar campos analiticos: " + e.getMessage());
        }

        procRepo.save(article);
        log.info("[ENTITY-WORKER] DONE: tags=" + article.getAiTags()
                + " scope=" + article.getScope()
                + " tone=" + article.getTone());
    }

    private String buildTags(String entitiesJson, String aiCategories) {
        Set<String> tags = new LinkedHashSet<>();

        try {
            JsonNode node = mapper.readTree(entitiesJson);

            node.path("topics").forEach(t -> {
                String slug = slugify(t.asText(""));
                if (!slug.isBlank()) tags.add(slug);
            });

            node.path("countries").forEach(c -> {
                String slug = "pais-" + slugify(c.asText(""));
                if (slug.length() > 5) tags.add(slug);
            });

            node.path("organizations").forEach(o -> {
                String slug = "org-" + slugify(o.asText(""));
                if (slug.length() > 4) tags.add(slug);
            });

            String scope = node.path("scope").asText("");
            if (!scope.isBlank()) tags.add("escopo-" + scope);

            String tone = node.path("tone").asText("");
            if (!tone.isBlank()) tags.add("tom-" + tone);

            JsonNode loc = node.path("location");
            String state = loc.path("state").asText("").trim();
            String city = loc.path("city").asText("").trim();
            if (!state.isBlank() && !state.equals("null")) tags.add("estado-" + slugify(state));
            if (!city.isBlank() && !city.equals("null")) tags.add("cidade-" + slugify(city));

            if (node.path("hasVictims").asBoolean(false)) tags.add("tem-vitimas");
        } catch (Exception e) {
            log.warning("[ENTITY-WORKER] buildTags parse: " + e.getMessage());
        }

        if (aiCategories != null) {
            for (String cat : aiCategories.split(",")) {
                String slug = slugify(cat.trim());
                if (!slug.isBlank()) tags.add(slug);
            }
        }

        return String.join(",", tags);
    }

    public static String slugify(String input) {
        if (input == null || input.isBlank()) return "";
        String normalized = Normalizer.normalize(input.trim().toLowerCase(), Normalizer.Form.NFD);
        return normalized
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
    }

    private String safe(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "..." : s.trim();
    }
}
