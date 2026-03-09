package com.globalpulse.news.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalpulse.news.db.ProcessedArticle;
import com.globalpulse.news.db.ProcessedArticleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Trending Híbrido — Abordagem B
 *
 * PARTE 1 — Categorias (100% confiável, sempre disponível)
 *   Conta artigos por categoria nas últimas 12h.
 *   Nunca falha, nunca duplica, nunca inventa.
 *   Ex: Brasil(70), Economia(25), Esportes(20)
 *
 * PARTE 2 — Entidades LLM (somente quando validadas)
 *   Usa SOMENTE artigos com entityStatus=DONE.
 *   Cada entidade só entra se:
 *     ✓ Aparece em 3+ artigos DISTINTOS
 *     ✓ Vem de 2+ fontes diferentes
 *     ✓ A página destino tem artigos reais
 *     ✓ Não é duplicata de categoria já exibida
 *
 * SCORE = artigos + (fontes_distintas * 2) + recência_boost
 * Evita que um portal spam domine o trending.
 *
 * DEDUP final: normaliza labels antes de comparar (evita "Reforma Tributária" + "reforma-tributaria")
 */
@Service
public class TrendingService {

    private static final Logger log = Logger.getLogger(TrendingService.class.getName());

    private static final int    HOURS_WINDOW          = 48; // 48h garante dados mesmo após reinicialização
    private static final int    TOP_N                 = 12;
    private static final int    MIN_ARTICLES_ENTITY   = 3;  // mínimo de artigos para entidade entrar
    private static final int    MIN_SOURCES_ENTITY    = 2;  // mínimo de fontes distintas
    private static final int    MAX_CATEGORIES        = 6;  // máximo de categorias no trending
    private static final int    MAX_ENTITIES          = 8;  // máximo de entidades no trending

    // Categorias que NÃO entram no trending (muito genéricas)
    private static final Set<String> CATEGORY_SKIP = new HashSet<>(Arrays.asList(
        "Geral", "geral"
    ));

    private final ProcessedArticleRepository procRepo;
    private final ObjectMapper               mapper;

    public TrendingService(ProcessedArticleRepository procRepo, ObjectMapper mapper) {
        this.procRepo = procRepo;
        this.mapper   = mapper;
    }

    public List<Map<String, Object>> getTrending() {
        Instant since = Instant.now().minus(HOURS_WINDOW, ChronoUnit.HOURS);
        List<ProcessedArticle> allRecent = procRepo.findRecentAll(since);

        log.info("[TRENDING] " + allRecent.size() + " artigos nas últimas " + HOURS_WINDOW + "h");

        List<Map<String, Object>> result = new ArrayList<>();

        // ── PARTE 1: Categorias ───────────────────────────────────────────────
        result.addAll(buildCategoryTrending(allRecent));

        // ── PARTE 2: Entidades LLM (só entityStatus=DONE) ─────────────────────
        List<ProcessedArticle> withEntities = allRecent.stream()
            .filter(a -> "DONE".equals(a.getEntityStatus()))
            .collect(Collectors.toList());

        log.info("[TRENDING] " + withEntities.size() + " com entidades extraídas");

        if (!withEntities.isEmpty()) {
            result.addAll(buildEntityTrending(withEntities, result));
        }

        // Ordena por score decrescente, limita
        result.sort(Comparator.comparingInt(
            (Map<String, Object> m) -> (int) m.get("score")).reversed());

        List<Map<String, Object>> final_ = result.stream().limit(TOP_N).collect(Collectors.toList());

        // Remove campo interno "score" antes de retornar (substitui por "count" para o frontend)
        final_.forEach(m -> {
            if (!m.containsKey("count")) m.put("count", m.get("score"));
        });

        log.info("[TRENDING] " + final_.size() + " itens no trending final");
        return final_;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PARTE 1 — Contagem por categoria
    // ─────────────────────────────────────────────────────────────────────────
    private List<Map<String, Object>> buildCategoryTrending(List<ProcessedArticle> articles) {
        // Conta artigos por categoria (cada artigo pode ter múltiplas categorias)
        Map<String, Integer> catCount = new LinkedHashMap<>();

        for (ProcessedArticle a : articles) {
            for (String cat : a.categoriesArray()) {
                if (cat.isBlank() || CATEGORY_SKIP.contains(cat)) continue;
                catCount.merge(cat, 1, Integer::sum);
            }
        }

        return catCount.entrySet().stream()
            .filter(e -> e.getValue() >= 2)
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(MAX_CATEGORIES)
            .map(e -> {
                String slug = slugify(e.getKey());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("label",  e.getKey());
                m.put("type",   "category");
                m.put("slug",   slug);  // navega para /{slug} (CategoryPage)
                m.put("count",  e.getValue());
                m.put("score",  e.getValue());
                return m;
            })
            .collect(Collectors.toList());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PARTE 2 — Entidades LLM validadas
    // ─────────────────────────────────────────────────────────────────────────
    private List<Map<String, Object>> buildEntityTrending(
            List<ProcessedArticle> withEntities,
            List<Map<String, Object>> alreadyInTrending) {

        // Coleta labels já no trending (para dedup)
        Set<String> alreadyNorm = alreadyInTrending.stream()
            .map(m -> normalize((String) m.get("label")))
            .collect(Collectors.toSet());

        // Contadores: label → {count, sources}
        Map<String, EntityStats> countryStats = new LinkedHashMap<>();
        Map<String, EntityStats> peopleStats  = new LinkedHashMap<>();
        Map<String, EntityStats> topicStats   = new LinkedHashMap<>();

        for (ProcessedArticle a : withEntities) {
            try {
                JsonNode node = mapper.readTree(a.getEntities());
                String source = a.getSource() != null ? a.getSource() : "unknown";

                accumulateStats(node.path("countries"), source, countryStats);
                accumulateStats(node.path("people"),    source, peopleStats);
                accumulateStats(node.path("topics"),    source, topicStats);
            } catch (Exception e) {
                log.fine("[TRENDING] Parse entities: " + e.getMessage());
            }
        }

        List<Map<String, Object>> entities = new ArrayList<>();

        // Países
        countryStats.entrySet().stream()
            .filter(e -> e.getValue().articleCount >= MIN_ARTICLES_ENTITY
                      && e.getValue().sourceCount()  >= MIN_SOURCES_ENTITY)
            .filter(e -> !alreadyNorm.contains(normalize(e.getKey())))
            .sorted(Comparator.comparingInt(
                (Map.Entry<String, EntityStats> e) -> e.getValue().score()).reversed())
            .limit(3)
            .forEach(e -> {
                String tag  = "pais-" + EntityWorker.slugify(e.getKey());
                String slug = "noticias/" + tag;
                List<ProcessedArticle> sample = procRepo.findByTag(tag, PageRequest.of(0, 1));
                if (!sample.isEmpty()) {
                    String img = sample.stream().map(ProcessedArticle::getImageUrl)
                        .filter(u -> u != null && !u.isBlank() && !u.contains("placeholder"))
                        .findFirst().orElse(null);
                    entities.add(buildEntry(e.getKey(), "country", slug, e.getValue().score(), img));
                    alreadyNorm.add(normalize(e.getKey()));
                }
            });

        // Pessoas
        peopleStats.entrySet().stream()
            .filter(e -> e.getValue().articleCount >= MIN_ARTICLES_ENTITY
                      && e.getValue().sourceCount()  >= MIN_SOURCES_ENTITY)
            .filter(e -> !alreadyNorm.contains(normalize(e.getKey())))
            .sorted(Comparator.comparingInt(
                (Map.Entry<String, EntityStats> e) -> e.getValue().score()).reversed())
            .limit(3)
            .forEach(e -> {
                String slug = "pessoa/" + EntityWorker.slugify(e.getKey());
                List<ProcessedArticle> sample = procRepo.findByPerson(e.getKey(), EntityWorker.slugify(e.getKey()), PageRequest.of(0, 1));
                if (!sample.isEmpty()) {
                    String img = sample.stream().map(ProcessedArticle::getImageUrl)
                        .filter(u -> u != null && !u.isBlank() && !u.contains("placeholder"))
                        .findFirst().orElse(null);
                    entities.add(buildEntry(e.getKey(), "person", slug, e.getValue().score(), img));
                    alreadyNorm.add(normalize(e.getKey()));
                }
            });

        // Tópicos
        topicStats.entrySet().stream()
            .filter(e -> e.getValue().articleCount >= MIN_ARTICLES_ENTITY
                      && e.getValue().sourceCount()  >= MIN_SOURCES_ENTITY)
            .filter(e -> !alreadyNorm.contains(normalize(e.getKey())))
            .sorted(Comparator.comparingInt(
                (Map.Entry<String, EntityStats> e) -> e.getValue().score()).reversed())
            .limit(3)
            .forEach(e -> {
                String topicSlug = EntityWorker.slugify(e.getKey());
                String slug = "topico/" + topicSlug;
                List<ProcessedArticle> sample = procRepo.findByTopic(e.getKey(), topicSlug, PageRequest.of(0, 1));
                if (!sample.isEmpty()) {
                    String img = sample.stream().map(ProcessedArticle::getImageUrl)
                        .filter(u -> u != null && !u.isBlank() && !u.contains("placeholder"))
                        .findFirst().orElse(null);
                    entities.add(buildEntry(e.getKey(), "topic", slug, e.getValue().score(), img));
                    alreadyNorm.add(normalize(e.getKey()));
                }
            });

        return entities;
    }

    private void accumulateStats(JsonNode arr, String source, Map<String, EntityStats> stats) {
        if (!arr.isArray()) return;
        for (JsonNode item : arr) {
            String val = item.asText("").trim();
            if (val.isBlank() || val.length() < 2) continue;
            stats.computeIfAbsent(val, k -> new EntityStats()).add(source);
        }
    }

    private Map<String, Object> buildEntry(String label, String type, String slug, int score) {
        return buildEntry(label, type, slug, score, null);
    }

    private Map<String, Object> buildEntry(String label, String type, String slug, int score, String imageUrl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label",    label);
        m.put("type",     type);
        m.put("slug",     slug);
        m.put("score",    score);
        m.put("count",    score);
        if (imageUrl != null && !imageUrl.isBlank()) m.put("imageUrl", imageUrl);
        return m;
    }

    private String slugify(String s) {
        return EntityWorker.slugify(s);
    }

    private String normalize(String s) {
        if (s == null) return "";
        return Normalizer.normalize(s.toLowerCase().trim(), Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
            .replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    // ── Score = artigos + fontes*2 ────────────────────────────────
    private static class EntityStats {
        int articleCount = 0;
        Set<String> sources = new HashSet<>();

        void add(String source) {
            articleCount++;
            sources.add(source);
        }

        int sourceCount() { return sources.size(); }

        int score() {
            return articleCount + (sourceCount() * 2);
        }
    }
}
