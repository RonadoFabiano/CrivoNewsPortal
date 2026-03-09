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
 * Clusteriza notícias que falam do mesmo assunto.
 *
 * Algoritmo (Jaccard similarity, sem ML):
 *  1. Tokeniza títulos removendo stopwords
 *  2. Agrupa por similaridade >= 0.30
 *  3. Nomeia cluster pelo artigo mais recente
 */
@Service
public class ClusterService {

    private static final Logger log      = Logger.getLogger(ClusterService.class.getName());
    private static final double THRESHOLD = 0.30;
    private static final int    MIN_SIZE  = 2;
    private static final int    MAX_ARTS  = 200;

    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
        "a","o","e","de","da","do","em","no","na","os","as","um","uma",
        "com","para","por","que","se","ao","dos","das","nos","nas","mas",
        "seu","sua","seus","suas","foi","são","está","como","mais","pelo",
        "pela","pelos","pelas","após","sobre","entre","quando","também",
        "pode","isso","esta","esse","eles","elas","diz","faz"
    ));

    private final ProcessedArticleRepository procRepo;

    public ClusterService(ProcessedArticleRepository procRepo) {
        this.procRepo = procRepo;
    }

    public List<Map<String, Object>> getClusters() {
        List<ProcessedArticle> articles = procRepo.findAllReady(PageRequest.of(0, MAX_ARTS));
        log.info("[CLUSTER] Clusterizando " + articles.size() + " artigos...");

        // Tokeniza cada artigo
        List<ArticleTokens> tokenized = new ArrayList<>();
        for (ProcessedArticle a : articles) {
            Set<String> tokens = tokenize(a.getAiTitle());
            if (tokens.size() >= 3) tokenized.add(new ArticleTokens(a, tokens));
        }

        // Agrupa por similaridade
        List<List<ArticleTokens>> clusters = new ArrayList<>();
        boolean[] assigned = new boolean[tokenized.size()];

        for (int i = 0; i < tokenized.size(); i++) {
            if (assigned[i]) continue;
            List<ArticleTokens> cluster = new ArrayList<>();
            cluster.add(tokenized.get(i));
            assigned[i] = true;

            for (int j = i + 1; j < tokenized.size(); j++) {
                if (assigned[j]) continue;
                if (jaccard(tokenized.get(i).tokens, tokenized.get(j).tokens) >= THRESHOLD) {
                    cluster.add(tokenized.get(j));
                    assigned[j] = true;
                }
            }

            if (cluster.size() >= MIN_SIZE) clusters.add(cluster);
        }

        // Serializa
        List<Map<String, Object>> result = clusters.stream()
            .map(this::toMap)
            .sorted(Comparator.comparingInt((Map<String, Object> m) -> (int) m.get("size")).reversed())
            .limit(50)
            .collect(Collectors.toList());

        log.info("[CLUSTER] " + result.size() + " clusters encontrados");
        return result;
    }

    private Map<String, Object> toMap(List<ArticleTokens> cluster) {
        ProcessedArticle leader = cluster.stream()
            .map(t -> t.article)
            .max(Comparator.comparing(a ->
                a.getPublishedAt() != null ? a.getPublishedAt() : java.time.Instant.EPOCH))
            .orElse(cluster.get(0).article);

        Set<String> common = new HashSet<>(cluster.get(0).tokens);
        for (ArticleTokens t : cluster) common.retainAll(t.tokens);
        String slug = common.stream().sorted().limit(3).collect(Collectors.joining("-"));
        if (slug.isBlank()) slug = EntityWorker.slugify(leader.getAiTitle());

        List<Map<String, Object>> arts = new ArrayList<>();
        for (ArticleTokens t : cluster) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slug",        t.article.getSlug());
            m.put("title",       t.article.getAiTitle());
            m.put("source",      t.article.getSource());
            m.put("publishedAt", t.article.getPublishedAt());
            m.put("image",       t.article.getImageUrl());
            arts.add(m);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cluster_slug", slug);
        m.put("size",         cluster.size());
        m.put("label",        leader.getAiTitle());
        m.put("image",        leader.getImageUrl());
        m.put("publishedAt",  leader.getPublishedAt());
        m.put("articles",     arts);
        return m;
    }

    private Set<String> tokenize(String title) {
        if (title == null || title.isBlank()) return Collections.emptySet();
        String n = Normalizer.normalize(title.toLowerCase(), Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
            .replaceAll("[^a-z0-9\\s]", " ");
        Set<String> result = new HashSet<>();
        for (String t : n.split("\\s+")) {
            if (t.length() > 2 && !STOPWORDS.contains(t)) result.add(t);
        }
        return result;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a); inter.retainAll(b);
        Set<String> union = new HashSet<>(a); union.addAll(b);
        return (double) inter.size() / union.size();
    }

    // Classe interna simples (sem record — mais compatível com proxies Spring)
    private static class ArticleTokens {
        final ProcessedArticle article;
        final Set<String>      tokens;
        ArticleTokens(ProcessedArticle article, Set<String> tokens) {
            this.article = article;
            this.tokens  = tokens;
        }
    }
}
