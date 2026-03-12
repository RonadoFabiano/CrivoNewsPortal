package com.globalpulse.news.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalpulse.news.db.ProcessedArticle;
import com.globalpulse.news.db.ProcessedArticleRepository;
import com.globalpulse.news.service.EntityWorker;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@RestController
public class SitemapController {

    private static final String BASE_URL = "https://crivo.news";
    private static final int ARTICLE_LIMIT = 5000;

    private static final String[] CATEGORIES = {
            "brasil", "mundo", "politica", "economia", "negocios",
            "tecnologia", "esportes", "ciencia", "saude",
            "educacao", "entretenimento", "cotidiano", "justica", "cultura", "geral"
    };

    private static final String[] STATIC_PAGES = {
            "/",
            "/trending",
            "/mais-lidas",
            "/clusters",
            "/sobre",
            "/contato",
            "/politica-editorial",
            "/busca",
            "/mapa"
    };

    private final ProcessedArticleRepository procRepo;
    private final ObjectMapper mapper;

    public SitemapController(ProcessedArticleRepository procRepo, ObjectMapper mapper) {
        this.procRepo = procRepo;
        this.mapper = mapper;
    }

    @Cacheable(value = "sitemap", key = "'sitemap'")
    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        List<ProcessedArticle> articles = procRepo.findAllReady(PageRequest.of(0, ARTICLE_LIMIT));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

        Set<String> tagPages = new LinkedHashSet<>();
        Set<String> countryPages = new LinkedHashSet<>();
        Set<String> personPages = new LinkedHashSet<>();
        Set<String> topicPages = new LinkedHashSet<>();
        Set<String> explicaPages = new LinkedHashSet<>();

        for (ProcessedArticle article : articles) {
            collectTagPages(article, tagPages, countryPages, topicPages, explicaPages);
            collectEntityPages(article, countryPages, personPages, topicPages, explicaPages);
        }

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"\n");
        xml.append("        xmlns:news=\"http://www.google.com/schemas/sitemap-news/0.9\">\n\n");

        for (String path : STATIC_PAGES) {
            appendUrl(xml, BASE_URL + path, "/".equals(path) ? "1.0" : "0.8", "/".equals(path) ? "daily" : "hourly", null);
        }

        for (String cat : CATEGORIES) {
            appendUrl(xml, BASE_URL + "/" + cat, "0.8", "hourly", null);
        }

        appendDynamicUrls(xml, tagPages, "0.7", "daily");
        appendDynamicUrls(xml, countryPages, "0.7", "daily");
        appendDynamicUrls(xml, personPages, "0.7", "daily");
        appendDynamicUrls(xml, topicPages, "0.7", "daily");
        appendDynamicUrls(xml, explicaPages, "0.6", "weekly");

        for (ProcessedArticle article : articles) {
            String url = BASE_URL + "/noticia/" + article.getSlug();
            String date = article.getPublishedAt() != null ? fmt.format(article.getPublishedAt()) : fmt.format(Instant.now());

            xml.append("  <url>\n");
            xml.append("    <loc>").append(escapeXml(url)).append("</loc>\n");
            xml.append("    <lastmod>").append(date).append("</lastmod>\n");
            xml.append("    <changefreq>never</changefreq>\n");
            xml.append("    <priority>0.7</priority>\n");
            xml.append("    <news:news>\n");
            xml.append("      <news:publication>\n");
            xml.append("        <news:name>CRIVO News</news:name>\n");
            xml.append("        <news:language>pt</news:language>\n");
            xml.append("      </news:publication>\n");
            xml.append("      <news:publication_date>").append(date).append("</news:publication_date>\n");
            xml.append("      <news:title>").append(escapeXml(article.getAiTitle() != null ? article.getAiTitle() : "")).append("</news:title>\n");
            xml.append("    </news:news>\n");
            xml.append("  </url>\n\n");
        }

        xml.append("</urlset>");

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header("Cache-Control", "public, max-age=3600")
                .body(xml.toString());
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> robots() {
        String body = "User-agent: *\n"
                + "Allow: /\n"
                + "Disallow: /api/\n\n"
                + "Sitemap: " + BASE_URL + "/sitemap.xml\n";
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body(body);
    }

    private void collectTagPages(ProcessedArticle article,
                                 Set<String> tagPages,
                                 Set<String> countryPages,
                                 Set<String> topicPages,
                                 Set<String> explicaPages) {
        if (article.getAiTags() == null || article.getAiTags().isBlank()) {
            return;
        }

        for (String rawTag : article.getAiTags().split(",")) {
            String tag = rawTag == null ? "" : rawTag.trim();
            if (tag.isBlank()) continue;

            tagPages.add(BASE_URL + "/noticias/" + tag);
            if (tag.startsWith("pais-")) {
                countryPages.add(BASE_URL + "/pais/" + tag.substring("pais-".length()));
                continue;
            }
            if (tag.startsWith("org-") || tag.startsWith("estado-") || tag.startsWith("cidade-") || tag.startsWith("escopo-") || tag.startsWith("tom-")) {
                continue;
            }
            topicPages.add(BASE_URL + "/topico/" + tag);
            explicaPages.add(BASE_URL + "/explica/" + tag);
        }
    }

    private void collectEntityPages(ProcessedArticle article,
                                    Set<String> countryPages,
                                    Set<String> personPages,
                                    Set<String> topicPages,
                                    Set<String> explicaPages) {
        if (article.getEntities() == null || article.getEntities().isBlank()) {
            return;
        }

        try {
            JsonNode node = mapper.readTree(article.getEntities());

            node.path("countries").forEach(country -> {
                String slug = EntityWorker.slugify(country.asText(""));
                if (!slug.isBlank()) countryPages.add(BASE_URL + "/pais/" + slug);
            });

            node.path("people").forEach(person -> {
                String slug = EntityWorker.slugify(person.asText(""));
                if (!slug.isBlank()) personPages.add(BASE_URL + "/pessoa/" + slug);
            });

            node.path("topics").forEach(topic -> {
                String slug = EntityWorker.slugify(topic.asText(""));
                if (!slug.isBlank()) {
                    topicPages.add(BASE_URL + "/topico/" + slug);
                    explicaPages.add(BASE_URL + "/explica/" + slug);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void appendDynamicUrls(StringBuilder xml, Set<String> urls, String priority, String changefreq) {
        for (String url : urls) {
            appendUrl(xml, url, priority, changefreq, null);
        }
    }

    private void appendUrl(StringBuilder xml, String loc, String priority, String changefreq, String lastmod) {
        xml.append("  <url>\n");
        xml.append("    <loc>").append(escapeXml(loc)).append("</loc>\n");
        if (lastmod != null) {
            xml.append("    <lastmod>").append(lastmod).append("</lastmod>\n");
        }
        xml.append("    <changefreq>").append(changefreq).append("</changefreq>\n");
        xml.append("    <priority>").append(priority).append("</priority>\n");
        xml.append("  </url>\n\n");
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
