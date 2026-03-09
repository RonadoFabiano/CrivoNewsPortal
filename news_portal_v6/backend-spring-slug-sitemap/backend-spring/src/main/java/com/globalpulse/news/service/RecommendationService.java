package com.globalpulse.news.service;

import com.globalpulse.news.db.ProcessedArticle;
import com.globalpulse.news.db.ProcessedArticleRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Recomenda artigos relacionados — "Você também pode gostar".
 *
 * Algoritmo:
 *  1. Tokeniza o artigo atual
 *  2. Compara com os últimos 100 artigos por similaridade de tokens
 *  3. Retorna top 4 mais similares (excluindo o próprio artigo)
 *  4. Fallback: artigos da mesma categoria
 */
@Service
public class RecommendationService {

    private static final Logger log = Logger.getLogger(RecommendationService.class.getName());
    private static final int    TOP_N = 4;

    private static final Set<String> STOPWORDS = Set.of(
        "a","o","e","de","da","do","em","no","na","os","as","um","uma",
        "com","para","por","que","se","ao","dos","das","nos","nas","mas",
        "seu","sua","foi","são","está","como","mais","pelo","pela","após",
        "sobre","entre","quando","também","pode","isso","esta","esse","diz"
    );

    private final ProcessedArticleRepository procRepo;

    public RecommendationService(ProcessedArticleRepository procRepo) {
        this.procRepo = procRepo;
    }
    public List<Map<String, Object>> recommend(String slug) {
        Optional<ProcessedArticle> currentOpt = procRepo.findBySlug(slug);
        if (currentOpt.isEmpty()) return List.of();

        ProcessedArticle current = currentOpt.get();
        Set<String> currentTokens = tokenize(current.getAiTitle() + " " + current.getAiDescription());

        // Pega candidatos recentes
        List<ProcessedArticle> candidates = procRepo.findAllReady(PageRequest.of(0, 40));

        // Score por similaridade
        List<Map<String, Object>> scored = candidates.stream()
            .filter(a -> !a.getSlug().equals(slug))
            .map(a -> {
                Set<String> tokens = tokenize(a.getAiTitle() + " " + a.getAiDescription());
                double sim = jaccard(currentTokens, tokens);

                // Boost se mesma categoria
                String[] currentCats = current.categoriesArray();
                for (String cat : a.categoriesArray()) {
                    for (String cc : currentCats) {
                        if (cc.equalsIgnoreCase(cat) && !cc.equalsIgnoreCase("Geral")) {
                            sim += 0.15;
                            break;
                        }
                    }
                }

                // Boost por entidades compartilhadas
                if (current.getEntities() != null && a.getEntities() != null) {
                    if (shareEntities(current.getEntities(), a.getEntities())) {
                        sim += 0.20;
                    }
                }

                Map<String, Object> m = new LinkedHashMap<>();
                m.put("slug",        a.getSlug());
                m.put("title",       a.getAiTitle());
                m.put("description", a.getAiDescription());
                m.put("image",       a.getImageUrl());
                m.put("source",      a.getSource());
                m.put("publishedAt", a.getPublishedAt());
                m.put("category",    a.categoriesArray()[0]);
                m.put("score",       sim);
                return m;
            })
            .filter(m -> (double) m.get("score") > 0.05)
            .sorted(Comparator.comparingDouble(
                (Map<String, Object> m) -> (double) m.get("score")).reversed())
            .limit(TOP_N)
            .collect(Collectors.toList());

        // Remove score do resultado final
        scored.forEach(m -> m.remove("score"));

        // Fallback: se menos de 2 similares, completa com mesma categoria
        if (scored.size() < 2 && current.getAiCategories() != null) {
            String cat = current.categoriesArray()[0];
            procRepo.findByCategory(cat, PageRequest.of(0, 10)).stream()
                .filter(a -> !a.getSlug().equals(slug))
                .filter(a -> scored.stream().noneMatch(s -> s.get("slug").equals(a.getSlug())))
                .limit(TOP_N - scored.size())
                .forEach(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("slug",        a.getSlug());
                    m.put("title",       a.getAiTitle());
                    m.put("description", a.getAiDescription());
                    m.put("image",       a.getImageUrl());
                    m.put("source",      a.getSource());
                    m.put("publishedAt", a.getPublishedAt());
                    m.put("category",    a.categoriesArray()[0]);
                    scored.add(m);
                });
        }

        log.info("[REC] " + scored.size() + " recomendações para: " + slug);
        return scored;
    }

    private boolean shareEntities(String entA, String entB) {
        // Checa se algum token de entities é comum (busca simples em string)
        String a = entA.toLowerCase(), b = entB.toLowerCase();
        // Extrai nomes entre aspas
        Set<String> namesA = extractNames(a);
        Set<String> namesB = extractNames(b);
        namesA.retainAll(namesB);
        return !namesA.isEmpty();
    }

    private Set<String> extractNames(String json) {
        Set<String> names = new HashSet<>();
        int i = 0;
        while (i < json.length()) {
            int s = json.indexOf('"', i);
            if (s < 0) break;
            int e = json.indexOf('"', s + 1);
            if (e < 0) break;
            String val = json.substring(s + 1, e).trim();
            if (val.length() > 2 && !val.matches("[\\[\\]{}:,]+")) names.add(val);
            i = e + 1;
        }
        return names;
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String normalized = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
            .replaceAll("[^a-z0-9\\s]", " ");
        return Arrays.stream(normalized.split("\\s+"))
            .filter(t -> t.length() > 2 && !STOPWORDS.contains(t))
            .collect(Collectors.toSet());
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a); inter.retainAll(b);
        Set<String> union = new HashSet<>(a); union.addAll(b);
        return (double) inter.size() / union.size();
    }
}
