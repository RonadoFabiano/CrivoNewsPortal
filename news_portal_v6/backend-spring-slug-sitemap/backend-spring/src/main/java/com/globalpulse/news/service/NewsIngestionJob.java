package com.globalpulse.news.service;

import com.globalpulse.news.api.NewsItem;
import com.globalpulse.news.api.RssService;
import com.globalpulse.news.api.ScraperOrchestrator;
import com.globalpulse.news.db.RawArticle;
import com.globalpulse.news.db.RawArticleRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.text.Normalizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Stream;

@Service
public class NewsIngestionJob {

    private static final Logger log = Logger.getLogger(NewsIngestionJob.class.getName());
    private static final int    MAX_PER_CYCLE      = 20;
    private static final double JACCARD_THRESHOLD  = 0.60; // títulos >= 60% similares = duplicata
    private static final int    DEDUP_WINDOW_HOURS = 24;   // só compara com artigos das últimas 24h

    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
        "a","o","e","de","da","do","em","no","na","os","as","um","uma",
        "com","para","por","que","se","ao","dos","das","nos","nas","mas",
        "seu","sua","foi","são","está","como","mais","pelo","pela","após",
        "sobre","entre","quando","também","pode","isso","esta","esse","diz"
    ));

    private final RssService           rssService;
    private final ScraperOrchestrator  scraperOrchestrator;
    private final ArticleExtractor     extractor;
    private final RawArticleRepository repo;

    // Cache em memória dos títulos recentes — evita queries ao banco a cada artigo
    // Mapa: slug → tokens do título — populado no início de cada ciclo
    private final Map<String, Set<String>> recentTitleTokens = new LinkedHashMap<>();

    public NewsIngestionJob(
            RssService rssService,
            ScraperOrchestrator scraperOrchestrator,
            ArticleExtractor extractor,
            RawArticleRepository repo
    ) {
        this.rssService          = rssService;
        this.scraperOrchestrator = scraperOrchestrator;
        this.extractor           = extractor;
        this.repo                = repo;
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 300_000)
    public void run() {
        try { ingestOnce(); }
        catch (Exception e) {
            log.warning("[INGESTION] Falhou: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }
    }

    public void ingestOnce() {
        log.info("═══════════════════════════════════════════════════");
        log.info("[INGESTION] Iniciando coleta — " + java.time.LocalDateTime.now());
        log.info("═══════════════════════════════════════════════════");

        List<NewsItem> rss     = rssService.fetch(null, 80);
        List<NewsItem> scraped = scraperOrchestrator.fetchAll();

        // Log por portal
        Map<String, Long> portalCount = new java.util.TreeMap<>();
        scraped.forEach(item -> {
            if (item.source() != null)
                portalCount.merge(item.source(), 1L, Long::sum);
        });
        log.info("[INGESTION] ── Resultado do Scraper ──");
        portalCount.forEach((portal, count) ->
            log.info("[INGESTION]   " + String.format("%-20s %3d itens", portal, count))
        );
        log.info("[INGESTION] RSS=" + rss.size() + " | Scraper=" + scraped.size()
                + " | Total bruto=" + (rss.size() + scraped.size()));

        // Combina e deduplica por slug dentro do lote atual
        List<NewsItem> merged = Stream.concat(rss.stream(), scraped.stream())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toMap(
                                NewsItem::slug,
                                n -> n,
                                (a, b) -> (a.image() != null && !a.image().startsWith("/")) ? a : b,
                                java.util.LinkedHashMap::new
                        ),
                        map -> new ArrayList<>(map.values())
                ));

        merged.sort(Comparator.comparing(
                (NewsItem n) -> n.publishedAt() == null ? Instant.EPOCH : n.publishedAt(),
                Comparator.reverseOrder()
        ));

        // Carrega títulos recentes do banco para o cache de deduplicação
        loadRecentTitleCache();

        int inserted = 0, skippedUrl = 0, skippedTitle = 0;

        for (NewsItem item : merged) {
            if (item == null || item.link() == null || item.link().isBlank()) continue;

            // ── Camada 1: dedup por URL normalizada ──────────────────
            String canonicalUrl = normalizeUrl(item.link());
            if (repo.findByCanonicalUrl(canonicalUrl).isPresent()) {
                skippedUrl++;
                continue;
            }

            // ── Camada 2: dedup por similaridade de título (Jaccard) ─
            if (item.title() != null && isTitleDuplicate(item.title())) {
                log.info("[INGESTION] Título similar já existe, ignorando: " + safe(item.title()));
                skippedTitle++;
                continue;
            }

            // Se o scraper já trouxe o HTML completo (portais Jsoup), usa direto
            // Se veio do RSS (sem HTML), busca agora via ArticleExtractor
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
            raw.setSource(item.source());
            raw.setOriginalCategory(item.category());
            raw.setPublishedAt(item.publishedAt() != null ? item.publishedAt() : Instant.now());
            raw.setAiStatus("PENDING");

            try {
                repo.save(raw);
                inserted++;

                // Adiciona ao cache para deduplicar os próximos itens do mesmo ciclo
                recentTitleTokens.put(raw.getSlug(), tokenize(raw.getRawTitle()));

                log.info("[INGESTION] Salvo PENDING: " + safe(item.title()));
            } catch (Exception e) {
                log.fine("[INGESTION] Duplicado ignorado (constraint): " + item.link());
            }

            if (inserted >= MAX_PER_CYCLE) break;
        }

        log.info("═══════════════════════════════════════════════════");
        log.info("[INGESTION] Ciclo concluído."
                + " Inseridos=" + inserted
                + " | SkipURL=" + skippedUrl
                + " | SkipTítulo=" + skippedTitle);
        log.info("[INGESTION] Banco → Total=" + repo.count()
                + " | PENDING=" + repo.countByAiStatus("PENDING")
                + " | DONE=" + repo.countByAiStatus("DONE")
                + " | FAILED=" + repo.countByAiStatus("FAILED"));
        log.info("═══════════════════════════════════════════════════");
    }

    /**
     * Carrega os títulos das últimas DEDUP_WINDOW_HOURS horas do banco
     * para o cache em memória. Chamado no início de cada ciclo.
     */
    private void loadRecentTitleCache() {
        recentTitleTokens.clear();
        Instant since = Instant.now().minus(DEDUP_WINDOW_HOURS, ChronoUnit.HOURS);
        try {
            repo.findRecentTitles(since).forEach(raw ->
                recentTitleTokens.put(raw.getSlug(), tokenize(raw.getRawTitle()))
            );
            log.info("[INGESTION] Cache de títulos carregado: " + recentTitleTokens.size() + " artigos recentes");
        } catch (Exception e) {
            log.warning("[INGESTION] Falha ao carregar cache de títulos: " + e.getMessage());
        }
    }

    /**
     * Verifica se um título é similar a algum artigo já existente.
     * Usa Jaccard similarity com threshold de 0.60.
     *
     * Exemplos que SÃO detectados como duplicata (Jaccard >= 0.60):
     *   "Fluminense anuncia contratação de Julián Millán"
     *   "Fluminense anuncia reforço com Julián Millán"         → 0.67 ✓ bloqueado
     *
     *   "Ataque com drones iranianos atinge estação da CIA"
     *   "Ataque com drones iranianos atinge estação da CIA"    → 1.00 ✓ bloqueado
     *
     * Exemplos que NÃO são bloqueados (notícias diferentes):
     *   "Lula sanciona reforma tributária"
     *   "Lula veta projeto de lei do Congresso"               → 0.20 ✗ passa
     */
    private boolean isTitleDuplicate(String title) {
        Set<String> tokens = tokenize(title);
        if (tokens.size() < 3) return false; // título muito curto — não compara

        for (Set<String> existing : recentTitleTokens.values()) {
            if (jaccard(tokens, existing) >= JACCARD_THRESHOLD) return true;
        }
        return false;
    }

    /**
     * Tokeniza título para comparação:
     * "Fluminense anuncia contratação de Julián Millán"
     *  → {"fluminense", "anuncia", "contratacao", "julian", "millan"}
     */
    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        String normalized = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD)
            .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
            .replaceAll("[^a-z0-9\\s]", " ");
        Set<String> tokens = new HashSet<>();
        for (String t : normalized.split("\\s+")) {
            if (t.length() > 2 && !STOPWORDS.contains(t)) tokens.add(t);
        }
        return tokens;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a); inter.retainAll(b);
        Set<String> union = new HashSet<>(a); union.addAll(b);
        return (double) inter.size() / union.size();
    }

    private String buildRawDescription(String rssDesc, String contentText) {
        StringBuilder sb = new StringBuilder();
        if (rssDesc != null && !rssDesc.isBlank()) sb.append(rssDesc.trim());
        if (contentText != null && contentText.length() > 100) {
            String preview = contentText.length() > 500
                    ? contentText.substring(0, 500) + "..."
                    : contentText;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(preview);
        }
        return sb.toString();
    }

    private String ensureUniqueSlug(String slug) {
        if (repo.findBySlug(slug).isEmpty()) return slug;
        for (int i = 2; i < 100; i++) {
            String candidate = slug + "-" + i;
            if (repo.findBySlug(candidate).isEmpty()) return candidate;
        }
        return slug + "-" + System.currentTimeMillis();
    }

    static String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return rawUrl;
        try {
            URI uri = new URI(rawUrl.trim());
            String query = uri.getQuery();
            String cleanQuery = null;
            if (query != null && !query.isBlank()) {
                String[] TRACKING = {
                    "utm_source","utm_medium","utm_campaign","utm_term","utm_content",
                    "utm_id","ref","source","origin","mc_cid","mc_eid","fbclid",
                    "gclid","yclid","_ga","trk","cid","sid"
                };
                List<String> kept = new ArrayList<>();
                for (String param : query.split("&")) {
                    String key = param.split("=")[0].toLowerCase();
                    boolean tracking = false;
                    for (String tp : TRACKING) {
                        if (key.equals(tp) || key.startsWith("utm_")) { tracking = true; break; }
                    }
                    if (!tracking) kept.add(param);
                }
                cleanQuery = kept.isEmpty() ? null : String.join("&", kept);
            }
            String path = uri.getPath();
            if (path != null && path.length() > 1 && path.endsWith("/"))
                path = path.substring(0, path.length() - 1);
            return new URI(uri.getScheme(), uri.getAuthority().toLowerCase(),
                    path, cleanQuery, null).toString();
        } catch (Exception e) {
            return rawUrl.trim();
        }
    }

    private String safe(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }
}
