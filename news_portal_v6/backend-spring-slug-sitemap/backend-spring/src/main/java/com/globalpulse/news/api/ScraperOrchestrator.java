package com.globalpulse.news.api;

import com.globalpulse.news.config.AppRuntimeProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

@Component
public class ScraperOrchestrator {

    private static final Logger log = Logger.getLogger(ScraperOrchestrator.class.getName());

    private final AppRuntimeProperties runtimeProperties;
    private final PublisherPreviewService previewService;
    private final List<PortalConfig> portals;
    private final List<NewsPortalScraper> portalScrapers;
    private final Map<String, CachedResult> portalCache = new ConcurrentHashMap<>();
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService portalExecutor = Executors.newFixedThreadPool(6);

    private volatile CachedResult allItemsCache = CachedResult.empty();
    private volatile boolean refreshRunning = false;

    public ScraperOrchestrator(AppRuntimeProperties runtimeProperties, PublisherPreviewService previewService) {
        this.runtimeProperties = runtimeProperties;
        this.previewService = previewService;
        this.portalScrapers = List.of(
                new ConfigurablePortalScraper("CNN Brasil", "www.cnnbrasil.com.br"),
                new ConfigurablePortalScraper("G1 Globo", "g1.globo.com"),
                new ConfigurablePortalScraper("Metropoles", "www.metropoles.com"),
                new InfoMoneyPortalScraper(),
                new ConfigurablePortalScraper("UOL", "noticias.uol.com.br"),
                new ConfigurablePortalScraper("Claudio Dantas", "claudiodantas.com.br"),
                new ConfigurablePortalScraper("Veja", "veja.abril.com.br"),
                new ConfigurablePortalScraper("Exame", "exame.com"),
                new ConfigurablePortalScraper("BBC Brasil", "www.bbc.com"),
                new ConfigurablePortalScraper("Jovem Pan", "jovempan.com.br"),
                new ForbesBrasilPortalScraper(),
                new ConfigurablePortalScraper("Poder360", "www.poder360.com.br")
        );

        this.portals = List.of(
                new PortalConfig("CNN Brasil", "https://www.cnnbrasil.com.br/", "geral", "www.cnnbrasil.com.br",
                        Set.of("politica", "nacional", "internacional", "economia", "esportes", "entretenimento"),
                        List.of("/ao-vivo/", "/videos/", "/blogs/", "/webstories/", "/newsletter/", "/author/")),
                new PortalConfig("G1 Globo", "https://g1.globo.com/", "geral", "g1.globo.com",
                        Set.of("politica", "economia", "mundo", "tecnologia", "ciencia", "saude", "pop-arte", "natureza", "educacao", "carros", "turismo", "bemestar", "meio-ambiente", "empreendedorismo"),
                        List.of("/ao-vivo/", "/guia-de-compras/", "/guia-de-carreiras/", "/imposto-de-renda/", "/loterias/", "/playlist/", "/podcast/", "/edicao/")),
                new PortalConfig("Metropoles", "https://www.metropoles.com/", "geral", "www.metropoles.com",
                        Set.of("brasil", "distrito-federal", "mundo", "negocios", "saude", "esportes", "entretenimento", "colunas"),
                        List.of("/autor/", "/tag/", "/categoria/", "/videos/", "/webstories/", "/galeria/")),
                new PortalConfig("InfoMoney", "https://www.infomoney.com.br/", "economia", "www.infomoney.com.br",
                        Set.of("mercados", "economia", "politica", "business", "minhas-financas", "onde-investir", "cotacoes", "carreira"),
                        List.of("/guias/", "/cursos/", "/planilhas/", "/webstories/", "/videos/", "/podcasts/", "/colunistas/")),
                new PortalConfig("UOL", "https://noticias.uol.com.br/", "geral", "noticias.uol.com.br",
                        Set.of("politica", "economia", "internacional", "cotidiano", "saude", "tecnologia", "educacao", "meio-ambiente"),
                        List.of("/ao-vivo/", "/videos/", "/tv/", "/play/", "/shopping/", "/nossa/", "/universa-hub/", "/mov/", "/album/")),
                new PortalConfig("Claudio Dantas", "https://claudiodantas.com.br/ultimas-noticias/", "politica", "claudiodantas.com.br",
                        Set.of(),
                        List.of("/categoria/", "/tag/", "/author/", "/wp-content/", "/podcast/", "/feed/")),
                new PortalConfig("Veja", "https://veja.abril.com.br/", "geral", "veja.abril.com.br",
                        Set.of("politica", "economia", "mundo", "saude", "tecnologia", "cultura", "coluna"),
                        List.of("/videos/", "/podcasts/", "/coluna/", "/noticias-sobre/", "/campeonatos/", "/autor/")),
                new PortalConfig("Exame", "https://exame.com/", "geral", "exame.com",
                        Set.of("brasil", "economia", "mundo", "mercado-imobiliario", "tecnologia", "invest", "insight", "negocios"),
                        List.of("/bussola/", "/esg/", "/pop/", "/webstories/", "/podcasts/", "/autor/")),
                new PortalConfig("BBC Brasil", "https://www.bbc.com/portuguese", "mundo", "www.bbc.com",
                        Set.of("portuguese"),
                        List.of("/portuguese/topics/", "/portuguese/resources/", "/portuguese/articles/topics/", "/portuguese/brazil-", "/portuguese/institutional/")),
                new PortalConfig("Jovem Pan", "https://jovempan.com.br/", "geral", "jovempan.com.br",
                        Set.of("noticias", "esportes", "entretenimento", "economia", "videos"),
                        List.of("/programas/", "/ao-vivo/", "/podcasts/", "/opiniao-jovem-pan/", "/autor/")),
                new PortalConfig("Forbes Brasil", "https://forbes.com.br/", "negocios", "forbes.com.br",
                        Set.of("forbes-money", "forbes-tech", "forbes-life", "forbesagro", "forbes-mulher", "forbesesg", "carreira", "negocios"),
                        List.of("/forbes-shop/", "/listas/", "/noticias-sobre/", "/under-30/", "/autor/", "/webstories/")),
                new PortalConfig("Poder360", "https://www.poder360.com.br/", "politica", "www.poder360.com.br",
                        Set.of("poder-congresso", "poder-governo", "poder-justica", "poder-eleicoes", "economia", "internacional", "opiniao"),
                        List.of("/poder-monitor/", "/agenda-do-poder/", "/infograficos/", "/videos/", "/podcasts/", "/pesquisas/"))
        ).stream().filter(this::isPortalEnabled).toList();
    }

    public List<NewsItem> fetchAll() {
        CachedResult cached = allItemsCache;
        if (cached.isValid(cacheTtl())) {
            log.info("[SCRAPER] Cache hit: " + cached.items().size() + " items");
            return cached.items();
        }

        if (!refreshRunning) {
            log.info("[SCRAPER] Cold cache, starting background refresh...");
            triggerAsyncRefresh();
        } else {
            log.info("[SCRAPER] Refresh already running...");
        }
        return cached.items();
    }

    @Scheduled(fixedDelayString = "${app.scraper.fixed-delay-ms:900000}")
    public void scheduledRefresh() {
        refreshAll();
    }

    public Map<String, Object> getCacheStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("cached", allItemsCache.isValid(cacheTtl()));
        status.put("refreshRunning", refreshRunning);
        status.put("total", allItemsCache.items().size());
        status.put("cachedAt", allItemsCache.createdAt() != null ? allItemsCache.createdAt().toString() : null);
        status.put("activePortals", getPortalNames());
        status.put("labOnlySource", runtimeProperties.getLab().getOnlySource());

        List<Map<String, Object>> perPortal = portals.stream()
                .map(portal -> {
                    CachedResult result = portalCache.get(portal.name());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("portal", portal.name());
                    row.put("count", result != null ? result.items().size() : 0);
                    row.put("cachedAt", result != null && result.createdAt() != null ? result.createdAt().toString() : null);
                    return row;
                })
                .toList();
        status.put("portals", perPortal);
        return status;
    }

    public List<String> getPortalNames() {
        return portals.stream().map(PortalConfig::name).toList();
    }

    private void triggerAsyncRefresh() {
        refreshExecutor.submit(this::refreshAll);
    }

    private synchronized void refreshAll() {
        if (refreshRunning) {
            return;
        }
        refreshRunning = true;
        Instant startedAt = Instant.now();
        try {
            log.info("[SCRAPER] Starting full refresh for " + portals.size() + " portals...");

            List<CompletableFuture<PortalFetchResult>> futures = portals.stream()
                    .map(portal -> CompletableFuture.supplyAsync(() -> refreshPortal(portal), portalExecutor))
                    .toList();

            List<PortalFetchResult> results = futures.stream()
                    .map(CompletableFuture::join)
                    .sorted(Comparator.comparing(PortalFetchResult::portalName))
                    .toList();

            LinkedHashMap<String, NewsItem> deduped = new LinkedHashMap<>();
            for (PortalFetchResult result : results) {
                for (NewsItem item : result.items()) {
                    String key = dedupeKey(item);
                    deduped.putIfAbsent(key, item);
                }
            }

            List<NewsItem> merged = new ArrayList<>(deduped.values());
            allItemsCache = new CachedResult(startedAt, List.copyOf(merged));

            log.info("[SCRAPER] Refresh complete. Total merged=" + merged.size());
            for (PortalFetchResult result : results) {
                log.info("[SCRAPER]   " + result.portalName() + " -> links=" + result.discoveredLinks() + " | extracted=" + result.items().size());
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "[SCRAPER] Full refresh failed", e);
        } finally {
            refreshRunning = false;
        }
    }

    private PortalFetchResult refreshPortal(PortalConfig portal) {
        Instant now = Instant.now();
        PortalScrapeRequest request = toPortalRequest(portal);
        NewsPortalScraper scraper = findScraper(request);
        if (scraper == null) {
            log.warning("[SCRAPER] No scraper found for " + portal.name());
            portalCache.put(portal.name(), new CachedResult(now, List.of()));
            return new PortalFetchResult(portal.name(), 0, List.of());
        }

        try {
            log.info("[SCRAPER] " + portal.name() + " [home]");
            Document home = Jsoup.connect(portal.homeUrl())
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                    .timeout(runtimeProperties.getScraper().getFetchTimeoutMs())
                    .followRedirects(true)
                    .get();

            List<String> links = scraper.scrapeHome(home, request, runtimeProperties.getScraper().getMaxPerPortal()).stream()
                    .map(link -> ScraperPortalSupport.normalizeArticleUrl(link, null))
                    .filter(link -> link != null && !link.isBlank())
                    .distinct()
                    .toList();

            log.info("[SCRAPER] " + portal.name() + " found " + links.size() + " links on home");

            List<NewsItem> items = new ArrayList<>();
            int index = 0;
            for (String link : links) {
                index++;
                NewsItem item = scraper.scrapeArticle(link, request, runtimeProperties.getScraper().getFetchTimeoutMs(), previewService);
                if (item != null) {
                    items.add(item);
                } else {
                    NewsItem fallback = buildFallbackNewsItem(link, portal);
                    if (fallback != null) {
                        items.add(fallback);
                    } else {
                        log.fine("[SCRAPER] " + portal.name() + " failed on " + link);
                    }
                }
                if (index % 25 == 0 || index == links.size()) {
                    log.info("[SCRAPER] " + portal.name() + " progress: " + index + "/" + links.size() + " | effective=" + items.size());
                }
            }

            portalCache.put(portal.name(), new CachedResult(now, List.copyOf(items)));
            return new PortalFetchResult(portal.name(), links.size(), List.copyOf(items));
        } catch (Exception e) {
            log.log(Level.WARNING, "[SCRAPER] Portal refresh failed for " + portal.name(), e);
            portalCache.put(portal.name(), new CachedResult(now, List.of()));
            return new PortalFetchResult(portal.name(), 0, List.of());
        }
    }

    private boolean isPortalEnabled(PortalConfig portal) {
        String onlySource = safeTrim(runtimeProperties.getLab().getOnlySource());
        if (!onlySource.isBlank()) {
            return portal.name().equalsIgnoreCase(onlySource);
        }

        List<String> activePortals = runtimeProperties.getScraper().getActivePortals();
        if (activePortals == null || activePortals.isEmpty()) {
            return true;
        }

        return activePortals.stream().anyMatch(name -> portal.name().equalsIgnoreCase(name));
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private Duration cacheTtl() {
        return Duration.ofMinutes(runtimeProperties.getScraper().getCacheTtlMinutes());
    }

    private NewsPortalScraper findScraper(PortalScrapeRequest portal) {
        return portalScrapers.stream()
                .filter(scraper -> scraper.supports(portal))
                .findFirst()
                .orElse(null);
    }

    private PortalScrapeRequest toPortalRequest(PortalConfig portal) {
        return new PortalScrapeRequest(
                portal.name(),
                portal.homeUrl(),
                portal.category(),
                portal.host(),
                portal.allowedSections(),
                portal.blockedPaths()
        );
    }

    private String dedupeKey(NewsItem item) {
        String link = ScraperPortalSupport.normalizeArticleUrl(item.link(), item.link());
        if (link != null && !link.isBlank()) {
            return link;
        }
        return item.source() + "::" + item.slug();
    }

    private NewsItem buildFallbackNewsItem(String link, PortalConfig portal) {
        String normalizedLink = ScraperPortalSupport.normalizeArticleUrl(link, link);
        if (normalizedLink == null || normalizedLink.isBlank()) {
            return null;
        }
        String title = fallbackTitleFromUrl(normalizedLink);
        if (title == null || title.isBlank()) {
            return null;
        }
        String image = previewService != null
                ? previewService.fetchBestImage(normalizedLink)
                    .filter(ScraperPortalSupport::isGoodImage)
                    .orElse("/placeholder.svg")
                : "/placeholder.svg";
        return new NewsItem(
                ScraperPortalSupport.generateSlug(title, normalizedLink),
                title,
                title,
                normalizedLink,
                portal.name(),
                image,
                Instant.now(),
                portal.category()
        );
    }

    private String fallbackTitleFromUrl(String url) {
        try {
            String path = new URI(url).getPath();
            if (path == null || path.isBlank()) {
                return null;
            }
            String slug = "";
            for (String part : path.split("/")) {
                if (!part.isBlank()) {
                    slug = part;
                }
            }
            if (slug.isBlank()) {
                return null;
            }
            slug = slug.replace(".ghtml", "").replace(".html", "").replace(".htm", "");
            String title = slug.replace('-', ' ').trim();
            if (title.length() < 8) {
                return null;
            }
            return Character.toUpperCase(title.charAt(0)) + title.substring(1);
        } catch (Exception e) {
            return null;
        }
    }

    private record CachedResult(Instant createdAt, List<NewsItem> items) {
        static CachedResult empty() {
            return new CachedResult(null, List.of());
        }

        boolean isValid(Duration ttl) {
            return createdAt != null && !items.isEmpty() && createdAt.plus(ttl).isAfter(Instant.now());
        }
    }

    private record PortalFetchResult(String portalName, int discoveredLinks, List<NewsItem> items) {
    }

    private record PortalConfig(
            String name,
            String homeUrl,
            String category,
            String host,
            Set<String> allowedSections,
            List<String> blockedPaths
    ) {
    }
}