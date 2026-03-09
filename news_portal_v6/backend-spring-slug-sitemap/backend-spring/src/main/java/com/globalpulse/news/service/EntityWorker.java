package com.globalpulse.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalpulse.news.ai.GroqEntityExtractor;
import com.globalpulse.news.db.ProcessedArticle;
import com.globalpulse.news.db.ProcessedArticleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;
import java.util.logging.Logger;

/**
 * Worker de entidades — roda após o AiSummaryWorker.
 * Processa artigos com entityStatus = null (ainda não extraímos entidades).
 *
 * Fluxo:
 *  1. Pega ProcessedArticles com entityStatus IS NULL
 *  2. Chama GroqEntityExtractor com título + descrição
 *  3. Salva JSON em entities + gera aiTags (slugs dos topics)
 *  4. Marca entityStatus = DONE | FAILED
 */
@Component
public class EntityWorker {

    private static final Logger log = Logger.getLogger(EntityWorker.class.getName());
    private static final int BATCH = 1; // 1 por ciclo para não disputar com AiSummaryWorker

    private final ProcessedArticleRepository procRepo;
    private final GroqEntityExtractor        extractor;
    private final ObjectMapper               mapper;

    public EntityWorker(ProcessedArticleRepository procRepo,
                        GroqEntityExtractor extractor,
                        ObjectMapper mapper) {
        this.procRepo  = procRepo;
        this.extractor = extractor;
        this.mapper    = mapper;
    }

    // Roda 30s após o AiSummaryWorker (que roda a cada 15s)
    @Scheduled(initialDelay = 75_000, fixedDelay = 35_000)
    public void runOne() {
        List<ProcessedArticle> queue = procRepo.findPendingEntities(PageRequest.of(0, BATCH));
        if (queue.isEmpty()) return;

        ProcessedArticle article = queue.get(0);
        log.info("[ENTITY-WORKER] Extraindo: \"" + safe(article.getAiTitle()) + "\"");

        String result = extractor.extract(article.getAiTitle(), article.getAiDescription());

        if (result == null) {
            article.setEntityStatus("FAILED");
            article.setEntities("{\"countries\":[],\"people\":[],\"organizations\":[]," +
                "\"topics\":[],\"location\":{\"state\":null,\"city\":null}," +
                "\"scope\":\"nacional\",\"tone\":\"neutro\"," +
                "\"hasVictims\":false,\"victimCount\":-1,\"keyFact\":\"\"}");
            procRepo.save(article);
            log.warning("[ENTITY-WORKER] FAILED: " + safe(article.getAiTitle()));
            return;
        }

        article.setEntities(result);
        article.setAiTags(buildTags(result, article.getAiCategories()));
        article.setEntityStatus("DONE");

        // Salva campos analíticos diretamente como colunas (para queries rápidas)
        try {
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(result);
            article.setScope(node.path("scope").asText("nacional"));
            article.setTone(node.path("tone").asText("neutro"));
            article.setKeyFact(node.path("keyFact").asText(""));
            article.setHasVictims(node.path("hasVictims").asBoolean(false));
            article.setVictimCount(node.path("victimCount").asInt(-1));
            com.fasterxml.jackson.databind.JsonNode loc = node.path("location");
            String st = loc.path("state").asText("").trim();
            String ct = loc.path("city").asText("").trim();
            article.setLocationState(st.equals("null") || st.isBlank() ? null : st);
            article.setLocationCity(ct.equals("null")  || ct.isBlank() ? null : ct);
        } catch (Exception e) {
            log.warning("[ENTITY-WORKER] Falhou ao salvar campos analíticos: " + e.getMessage());
        }

        procRepo.save(article);
        log.info("[ENTITY-WORKER] DONE: tags=" + article.getAiTags()
            + " scope=" + article.getScope()
            + " tone=" + article.getTone());
    }

    /**
     * Gera aiTags combinando todos os campos do novo EntityExtractor:
     *  - topics (slugificados)
     *  - países: "pais-ira"
     *  - organizações: "org-petrobras"
     *  - scope: "escopo-nacional"
     *  - tone: "tom-urgente"
     *  - localização: "estado-mg", "cidade-bh"
     *  - categorias existentes
     */
    private String buildTags(String entitiesJson, String aiCategories) {
        Set<String> tags = new LinkedHashSet<>();

        try {
            JsonNode node = mapper.readTree(entitiesJson);

            // Topics
            node.path("topics").forEach(t -> {
                String slug = slugify(t.asText(""));
                if (!slug.isBlank()) tags.add(slug);
            });

            // Países
            node.path("countries").forEach(c -> {
                String slug = "pais-" + slugify(c.asText(""));
                if (slug.length() > 5) tags.add(slug);
            });

            // Organizações
            node.path("organizations").forEach(o -> {
                String slug = "org-" + slugify(o.asText(""));
                if (slug.length() > 4) tags.add(slug);
            });

            // Scope e Tone como tags navegáveis
            String scope = node.path("scope").asText("");
            if (!scope.isBlank()) tags.add("escopo-" + scope);

            String tone = node.path("tone").asText("");
            if (!tone.isBlank()) tags.add("tom-" + tone);

            // Localização brasileira
            JsonNode loc = node.path("location");
            String state = loc.path("state").asText("").trim();
            String city  = loc.path("city").asText("").trim();
            if (!state.isBlank() && !state.equals("null"))
                tags.add("estado-" + slugify(state));
            if (!city.isBlank() && !city.equals("null"))
                tags.add("cidade-" + slugify(city));

            // Urgência
            if (node.path("hasVictims").asBoolean(false))
                tags.add("tem-vitimas");

        } catch (Exception e) {
            log.warning("[ENTITY-WORKER] buildTags parse: " + e.getMessage());
        }

        // Categorias existentes
        if (aiCategories != null) {
            for (String cat : aiCategories.split(",")) {
                String slug = slugify(cat.trim());
                if (!slug.isBlank()) tags.add(slug);
            }
        }

        return String.join(",", tags);
    }

    /** "Oriente Médio" → "oriente-medio", "Inteligência Artificial" → "inteligencia-artificial" */
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
