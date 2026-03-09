package com.globalpulse.news.api;

import com.globalpulse.news.db.ProcessedArticle;
import com.globalpulse.news.db.ProcessedArticleRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Gera sitemap.xml automático com:
 *  - Home page
 *  - Páginas de categoria (/brasil, /economia, etc.)
 *  - Todas as notícias (/noticia/{slug})
 *
 * Acesse: GET /sitemap.xml
 */
@RestController
public class SitemapController {

    private static final String BASE_URL = "https://crivo.news";

    private static final String[] CATEGORIES = {
        "brasil", "mundo", "politica", "economia", "negocios",
        "tecnologia", "esportes", "ciencia", "saude",
        "educacao", "entretenimento", "cotidiano", "justica", "cultura", "geral"
    };

    private final ProcessedArticleRepository procRepo;

    public SitemapController(ProcessedArticleRepository procRepo) {
        this.procRepo = procRepo;
    }

    @Cacheable(value = "sitemap", key = "'sitemap'")
    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        List<ProcessedArticle> articles = procRepo.findAllReady(PageRequest.of(0, 5000));

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"\n");
        xml.append("        xmlns:news=\"http://www.google.com/schemas/sitemap-news/0.9\">\n\n");

        // Home
        appendUrl(xml, BASE_URL + "/", "1.0", "daily", null);

        // Páginas especiais
        appendUrl(xml, BASE_URL + "/trending",   "0.9", "hourly", null);
        appendUrl(xml, BASE_URL + "/mais-lidas", "0.9", "hourly", null);
        appendUrl(xml, BASE_URL + "/clusters",   "0.8", "hourly", null);

        // Páginas de categoria
        for (String cat : CATEGORIES) {
            appendUrl(xml, BASE_URL + "/" + cat, "0.8", "hourly", null);
        }

        // Páginas de entidade (geradas dinamicamente)
        procRepo.findAllReady(PageRequest.of(0, 5000)).stream()
            .filter(a -> a.getAiTags() != null && !a.getAiTags().isBlank())
            .flatMap(a -> java.util.Arrays.stream(a.getAiTags().split(",")))
            .distinct()
            .filter(tag -> !tag.isBlank())
            .limit(2000)
            .forEach(tag -> appendUrl(xml, BASE_URL + "/noticias/" + tag, "0.6", "daily", null));

        // Páginas Explica (uma por tag/tópico)
        procRepo.findAllReady(PageRequest.of(0, 5000)).stream()
            .filter(a -> a.getAiTags() != null && !a.getAiTags().isBlank())
            .flatMap(a -> java.util.Arrays.stream(a.getAiTags().split(",")))
            .filter(t -> !t.startsWith("pais-") && !t.isBlank())
            .distinct().limit(500)
            .forEach(tag -> appendUrl(xml, BASE_URL + "/explica/" + tag, "0.7", "weekly", null));

        // Notícias individuais

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
        for (ProcessedArticle a : articles) {
            String url  = BASE_URL + "/noticia/" + a.getSlug();
            String date = a.getPublishedAt() != null
                ? fmt.format(a.getPublishedAt())
                : fmt.format(Instant.now());

            xml.append("  <url>\n");
            xml.append("    <loc>").append(escapeXml(url)).append("</loc>\n");
            xml.append("    <lastmod>").append(date).append("</lastmod>\n");
            xml.append("    <changefreq>never</changefreq>\n");
            xml.append("    <priority>0.7</priority>\n");
            // Google News sitemap extension
            xml.append("    <news:news>\n");
            xml.append("      <news:publication>\n");
            xml.append("        <news:name>CRIVO News</news:name>\n");
            xml.append("        <news:language>pt</news:language>\n");
            xml.append("      </news:publication>\n");
            xml.append("      <news:publication_date>").append(date).append("</news:publication_date>\n");
            xml.append("      <news:title>").append(escapeXml(a.getAiTitle() != null ? a.getAiTitle() : "")).append("</news:title>\n");
            xml.append("    </news:news>\n");
            xml.append("  </url>\n\n");
        }

        xml.append("</urlset>");

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_XML)
            .header("Cache-Control", "public, max-age=3600") // cache 1h
            .body(xml.toString());
    }

    /** robots.txt apontando para o sitemap */
    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> robots() {
        String body =
            "User-agent: *\n" +
            "Allow: /\n" +
            "Disallow: /api/\n\n" +
            "Sitemap: " + BASE_URL + "/sitemap.xml\n";
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_PLAIN)
            .body(body);
    }

    private void appendUrl(StringBuilder xml, String loc, String priority,
                           String changefreq, String lastmod) {
        xml.append("  <url>\n");
        xml.append("    <loc>").append(escapeXml(loc)).append("</loc>\n");
        if (lastmod  != null) xml.append("    <lastmod>").append(lastmod).append("</lastmod>\n");
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
