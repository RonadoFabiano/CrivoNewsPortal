package com.globalpulse.news.service;

import com.globalpulse.news.api.NewsItem;
import com.globalpulse.news.api.RssService;
import com.globalpulse.news.api.ScraperOrchestrator;
import com.globalpulse.news.config.AppRuntimeProperties;
import com.globalpulse.news.db.RawArticle;
import com.globalpulse.news.db.RawArticleRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Stream;

@Service
public class NewsIngestionJob {

    private static final Logger log = Logger.getLogger(NewsIngestionJob.class.getName());
    private static final double JACCARD_THRESHOLD = 0.60;
    private static final int DEDUP_WINDOW_HOURS = 24;
    private static final int MAX_SOURCE_LENGTH = 80;

    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "a", "o", "e", "de", "da", "do", "em", "no", "na", "os", "as", "um", "uma",
            "com", "para", "por", "que", "se", "ao", "dos", "das", "nos", "nas", "mas",
            "seu", "sua", "foi", "sao", "esta", "como", "mais", "pelo", "pela", "apos",
            "sobre", "entre", "quando", "tambem", "pode", "isso", "esta", "esse", "diz"
    ));

    private final RssService rssService;
    private final ScraperOrchestrator scraperOrchestrator;
    private final ArticleExtractor extractor;
    private final RawArticleRepository repo;
    private final AppRuntimeProperties runtimeProperties;
    private final Map<String, Set<String>> recentTitleTokens = new LinkedHashMap<>();

    public NewsIngestionJob(
            RssService rssService,
            ScraperOrchestrator scraperOrchestrator,
            ArticleExtractor extractor,
            RawArticleRepository repo,
            AppRuntimeProperties runtimeProperties
    ) {
        this.rssService = rssService;
        this.scraperOrchestrator = scraperOrchestrator;
        this.extractor = extractor;
        this.repo = repo;
        this.runtimeProperties = runtimeProperties;
    }

    @Scheduled(
            initialDelayString = "${app.ingestion.initial-delay-ms:15000}",
            fixedDelayString = "${app.ingestion.fixed-delay-ms:300000}"
    )
    public void run() {
        try {
            ingestOnce();
        } catch (Exception e) {
            log.warning("[INGESTION] Failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    public void ingestOnce() {
        String cycleId = UUID.randomUUID().toString().substring(0, 8);
        logCycle(cycleId, "==================================================");
        logCycle(cycleId, "Starting ingestion at " + LocalDateTime.now());
        logCycle(cycleId, "==================================================");

        boolean rssEnabled = runtimeProperties.getIngestion().isEnableRss();
        int maxPerCycle = runtimeProperties.getIngestion().getMaxPerCycle();

        List<NewsItem> rss = rssEnabled ? rssService.fetch(null, 80) : List.of();
        List<NewsItem> scraped = scraperOrchestrator.fetchAll();

        Map<String, Long> portalCount = new TreeMap<>();
        scraped.forEach(item -> {
            if (item != null) {
                String source = normalizeSource(item.source(), item.link());
                portalCount.merge(source, 1L, Long::sum);
            }
        });

        logCycle(cycleId, "Scraper results:");
        portalCount.forEach((portal, count) ->
                logCycle(cycleId, "  " + String.format("%-20s %3d items", portal, count))
        );
        logCycle(cycleId, "RSS=" + rss.size() + " | Scraper=" + scraped.size()
                + " | TotalRaw=" + (rss.size() + scraped.size()));

        List<NewsItem> merged = Stream.concat(rss.stream(), scraped.stream())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(
                                NewsItem::slug,
                                n -> n,
                                (a, b) -> (a.image() != null && !a.image().startsWith("/")) ? a : b,
                                LinkedHashMap::new
                        ),
                        map -> new ArrayList<>(map.values())
                ));

        merged.sort(Comparator.comparing(
                (NewsItem n) -> n.publishedAt() == null ? Instant.EPOCH : n.publishedAt(),
                Comparator.reverseOrder()
        ));

        loadRecentTitleCache(cycleId);

        int inserted = 0;
        int skippedUrl = 0;
        int skippedTitle = 0;

        for (NewsItem item : merged) {
            if (item == null || item.link() == null || item.link().isBlank()) {
                continue;
            }

            String canonicalUrl = normalizeUrl(item.link());
            if (repo.findByCanonicalUrl(canonicalUrl).isPresent()) {
                skippedUrl++;
                continue;
            }

            if (item.title() != null && isTitleDuplicate(item.title())) {
                logCycle(cycleId, "Similar title already exists, skipping: " + safe(item.title()));
                skippedTitle++;
                continue;
            }

            String html = (item.fullHtml() != null && !item.fullHtml().isBlank())
                    ? item.fullHtml()
                    : extractor.fetchHtml(item.link());

            RawArticle raw = new RawArticle();
            raw.setCanonicalUrl(canonicalUrl);
            raw.setSlug(ensureUniqueSlug(item.slug()));
            raw.setRawTitle(item.title() != null ? item.title() : "");
            raw.setRawDescription(item.description());
            raw.setHtmlContent(html.isBlank() ? null : html);
            raw.setNormalizeStatus(html.isBlank() ? "NORMALIZED" : "PENDING_NORMALIZE");
            raw.setRawContentText(null);
            raw.setImageUrl(item.image());
            raw.setSource(normalizeSource(item.source(), canonicalUrl));
            raw.setOriginalCategory(item.category());
            raw.setPublishedAt(item.publishedAt() != null ? item.publishedAt() : Instant.now());
            raw.setAiStatus("PENDING");

            try {
                repo.save(raw);
                inserted++;
                recentTitleTokens.put(raw.getSlug(), tokenize(raw.getRawTitle()));
                logCycle(cycleId, "Saved PENDING: " + safe(item.title()));
            } catch (Exception e) {
                log.fine(prefix(cycleId) + "Duplicate ignored by constraint: " + item.link());
            }

            if (inserted >= maxPerCycle) {
                break;
            }
        }

        logCycle(cycleId, "==================================================");
        logCycle(cycleId, "Cycle finished. Inserted=" + inserted
                + " | SkipUrl=" + skippedUrl
                + " | SkipTitle=" + skippedTitle);
        logCycle(cycleId, "Database -> Total=" + repo.count()
                + " | PENDING=" + repo.countByAiStatus("PENDING")
                + " | DONE=" + repo.countByAiStatus("DONE")
                + " | FAILED=" + repo.countByAiStatus("FAILED"));
        logCycle(cycleId, "==================================================");
    }

    private void loadRecentTitleCache(String cycleId) {
        recentTitleTokens.clear();
        Instant since = Instant.now().minus(DEDUP_WINDOW_HOURS, ChronoUnit.HOURS);
        try {
            repo.findRecentTitles(since).forEach(raw ->
                    recentTitleTokens.put(raw.getSlug(), tokenize(raw.getRawTitle()))
            );
            logCycle(cycleId, "Recent title cache loaded: " + recentTitleTokens.size() + " articles");
        } catch (Exception e) {
            log.warning(prefix(cycleId) + "Failed to load title cache: " + e.getMessage());
        }
    }

    private void logCycle(String cycleId, String message) {
        log.info(prefix(cycleId) + message);
    }

    private String prefix(String cycleId) {
        return "[INGESTION cid=" + cycleId + "] ";
    }

    private boolean isTitleDuplicate(String title) {
        Set<String> tokens = tokenize(title);
        if (tokens.size() < 3) {
            return false;
        }

        for (Set<String> existing : recentTitleTokens.values()) {
            if (jaccard(tokens, existing) >= JACCARD_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptySet();
        }
        String normalized = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replaceAll("[^a-z0-9\\s]", " ");
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() > 2 && !STOPWORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String normalizeSource(String source, String url) {
        String value = source != null ? source.trim() : "";
        if (value.isBlank() || value.length() > MAX_SOURCE_LENGTH || looksCorrupted(value)) {
            value = inferSourceFromUrl(url);
        }
        if (value.length() > MAX_SOURCE_LENGTH) {
            value = value.substring(0, MAX_SOURCE_LENGTH);
        }
        return value.isBlank() ? "Unknown" : value;
    }

    private boolean looksCorrupted(String value) {
        return value.contains("Ã")
                || value.contains("â")
                || value.contains("Â")
                || value.contains("�");
    }

    private String inferSourceFromUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null || host.isBlank()) {
                return "Unknown";
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            if (host.contains("metropoles.com")) return "Metropoles";
            if (host.contains("g1.globo.com")) return "G1 Globo";
            if (host.contains("cnnbrasil.com.br")) return "CNN Brasil";
            if (host.contains("infomoney.com.br")) return "InfoMoney";
            if (host.contains("forbes.com.br")) return "Forbes Brasil";
            if (host.contains("jovempan.com.br")) return "Jovem Pan";
            if (host.contains("bbc.com")) return "BBC Brasil";
            if (host.contains("veja.abril.com.br")) return "Veja";
            if (host.contains("exame.com")) return "Exame";
            if (host.contains("claudiodantas.com.br")) return "Claudio Dantas";
            if (host.contains("poder360.com.br")) return "Poder360";
            if (host.contains("uol.com.br")) return "UOL";
            String[] parts = host.split("\\.");
            if (parts.length >= 2) {
                return capitalizeToken(parts[parts.length - 2]);
            }
            return host;
        } catch (Exception ignored) {
            return "Unknown";
        }
    }

    private String capitalizeToken(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    private String ensureUniqueSlug(String slug) {
        if (repo.findBySlug(slug).isEmpty()) {
            return slug;
        }
        for (int i = 2; i < 100; i++) {
            String candidate = slug + "-" + i;
            if (repo.findBySlug(candidate).isEmpty()) {
                return candidate;
            }
        }
        return slug + "-" + System.currentTimeMillis();
    }

    static String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return rawUrl;
        }
        try {
            URI uri = new URI(rawUrl.trim());
            String query = uri.getQuery();
            String cleanQuery = null;
            if (query != null && !query.isBlank()) {
                String[] trackingKeys = {
                        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
                        "utm_id", "ref", "source", "origin", "mc_cid", "mc_eid", "fbclid",
                        "gclid", "yclid", "_ga", "trk", "cid", "sid"
                };
                List<String> kept = new ArrayList<>();
                for (String param : query.split("&")) {
                    String key = param.split("=")[0].toLowerCase(Locale.ROOT);
                    boolean tracking = false;
                    for (String trackingKey : trackingKeys) {
                        if (key.equals(trackingKey) || key.startsWith("utm_")) {
                            tracking = true;
                            break;
                        }
                    }
                    if (!tracking) {
                        kept.add(param);
                    }
                }
                cleanQuery = kept.isEmpty() ? null : String.join("&", kept);
            }
            String path = uri.getPath();
            if (path != null && path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return new URI(uri.getScheme(), uri.getAuthority().toLowerCase(Locale.ROOT), path, cleanQuery, null)
                    .toString();
        } catch (Exception e) {
            return rawUrl.trim();
        }
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        return compact.length() > 80 ? compact.substring(0, 80) + "..." : compact;
    }
}