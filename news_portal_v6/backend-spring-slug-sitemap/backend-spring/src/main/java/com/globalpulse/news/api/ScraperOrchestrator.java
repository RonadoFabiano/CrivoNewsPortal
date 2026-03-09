package com.globalpulse.news.api;

import com.rometools.rome.feed.synd.*;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

/**
 * ScraperOrchestrator — agrega portais via RSS + Jsoup.
 *
 * Técnica do POC (InfoMoney):
 *  - WHITELIST de seções válidas por portal (evita links de ferramentas, cursos, etc.)
 *  - Validação estrutural de URL: /secao/slug-com-hifens/ (≥3 segmentos, slug com hífen)
 *  - Referrer = home do portal (simula clique interno)
 *  - Rate limit: delay 150-500ms entre artigos
 *  - Status check: loga 403/429 explicitamente
 *  - Dois executores separados (portal/artigo) para evitar deadlock
 */
@Service
public class ScraperOrchestrator {

    private static final Logger log = Logger.getLogger(ScraperOrchestrator.class.getName());

    private static final Duration CACHE_TTL     = Duration.ofMinutes(20);
    private static final int      MAX_PER_PORTAL = 25; // era 10
    private static final int      FETCH_TIMEOUT  = 20_000; // 20s — CNN e portais pesados precisam de mais tempo

    private final Random rng = new Random();

    // ── User-Agents rotativos ────────────────────────────────────────────────
    private static final List<String> UAS = List.of(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    );
    private String ua() { return UAS.get(rng.nextInt(UAS.size())); }

    private void humanDelay() {
        try { Thread.sleep(150 + rng.nextInt(350)); } catch (InterruptedException ignored) {}
    }

    // ============================================================
    //  CONFIGURAÇÃO DOS PORTAIS — padrão do POC
    // ============================================================

    record PortalConfig(
        String name,
        String rssUrl,          // RSS nativo (null = Jsoup)
        String homeUrl,         // home ou seção para Jsoup
        String category,
        String host,            // ex: "www.infomoney.com.br"
        Set<String> allowedSections,  // whitelist de seções válidas
        List<String> blockedPaths     // paths que nunca são artigos
    ) {}

    private static final List<PortalConfig> PORTALS = List.of(

        // ── CNN Brasil ── Jsoup (100% taxa)
        new PortalConfig(
            "CNN Brasil",
            null,
            "https://www.cnnbrasil.com.br/",
            "Brasil",
            "www.cnnbrasil.com.br",
            Set.of("nacional","internacional","economia","tecnologia","esportes",
                   "entretenimento","saude","politica","business"),
            List.of("/author/","/tag/","/page/","/newsletter/","/assine/","/videos/")
        ),

        // ── G1 Globo ── Jsoup (100% taxa)
        new PortalConfig(
            "G1 Globo",
            null,
            "https://g1.globo.com/",
            "Geral",
            "g1.globo.com",
            Set.of("politica","economia","mundo","tecnologia","saude","educacao",
                   "ciencia","agropecuaria","turismo-e-viagem","natureza","pop-arte",
                   "sp","rj","mg","rs","ba","ce","pe","df","go","pr","sc"),
            List.of("/autor/","/tag/","/videos/","/ao-vivo/","/newsletter/")
        ),

        // ── Metrópoles ── Jsoup (100% taxa)
        new PortalConfig(
            "Metrópoles",
            null,
            "https://www.metropoles.com/",
            "Geral",
            "www.metropoles.com",
            Set.of("brasil","mundo","esportes","entretenimento","saude",
                   "tecnologia","economia","cultura","cidades"),
            List.of("/author/","/tag/","/page/","/assine/","/podcast/","/videos/","/colunas/")
        ),

        // ── InfoMoney ── Jsoup (100% taxa)
        new PortalConfig(
            "InfoMoney",
            null,
            "https://www.infomoney.com.br/",
            "Economia",
            "www.infomoney.com.br",
            Set.of("mercados","onde-investir","politica","economia","mundo","business",
                   "advisor","trader","minhas-financas","brasil","consumo","esportes",
                   "carreira","saude","cripto","investimentos","global"),
            List.of("/cotacoes/","/ferramentas/","/cursos/","/planilhas/","/ebooks/",
                    "/guias/","/relatorios/","/podcasts/","/videos/","/newsletter/","/assine/")
        ),

        // ── Claudio Dantas ── Jsoup (100% taxa)
        new PortalConfig(
            "Claudio Dantas",
            null,
            "https://claudiodantas.com.br/ultimas-noticias/",
            "Política",
            "claudiodantas.com.br",
            Collections.emptySet(),
            List.of("/author/","/tag/","/page/","/category/","/feed/","/envie-sua-pauta/")
        ),

        // ── Veja ── Jsoup (100% taxa)
        new PortalConfig(
            "Veja",
            null,
            "https://veja.abril.com.br/",
            "Geral",
            "veja.abril.com.br",
            Set.of("brasil","mundo","economia","saude","ciencia","educacao",
                   "tecnologia","cultura","entretenimento","comportamento"),
            List.of("/author/","/tag/","/page/","/newsletter/","/assine/")
        ),

        // ── Exame ── Jsoup (100% taxa)
        new PortalConfig(
            "Exame",
            null,
            "https://exame.com/",
            "Negócios",
            "exame.com",
            Set.of("brasil","negocios","economia","tecnologia","carreira","invest",
                   "mundo","ciencia","saude","esg","flash"),
            List.of("/author/","/tag/","/page/","/newsletter/","/assine/","/videos/")
        ),

        // ── BBC Brasil ── Jsoup (100% taxa)
        new PortalConfig(
            "BBC Brasil",
            null,
            "https://www.bbc.com/portuguese",
            "Internacional",
            "www.bbc.com",
            Collections.emptySet(),
            List.of("/sport/","/sounds/","/iplayer/","/weather/","/live/",
                    "/programmes/","/schedules/","/food/","/institutional-")
        ),

        // ── Jovem Pan ── Jsoup (100% taxa)
        new PortalConfig(
            "Jovem Pan",
            null,
            "https://jovempan.com.br/",
            "Geral",
            "jovempan.com.br",
            Set.of("noticias"),
            List.of("/author/","/tag/","/page/","/videos/","/radio/","/podcasts/",
                    "/ao-vivo/","/playlist/","/programas/","/opiniao-","/comentaristas/",
                    "/esportes/","/entretenimento/","/jovem-pan-")
        ),

        // ── Forbes Brasil ── Jsoup (100% taxa)
        new PortalConfig(
            "Forbes Brasil",
            null,
            "https://forbes.com.br/ultimas-noticias/",
            "Negócios",
            "forbes.com.br",
            Collections.emptySet(),
            List.of("/author/","/tag/","/page/","/newsletter/","/listas/",
                    "/anuncie","/sobre-a-forbes","/fale-conosco","/politica-",
                    "/termos-","/trabalhe-","/forbes-money/forbes-real-estate/","/forbesagro/")
        )
    );

    // ============================================================
    //  CACHE
    // ============================================================
    private record CachedResult(List<NewsItem> items, Instant expiresAt) {
        boolean isValid() { return expiresAt.isAfter(Instant.now()); }
    }

    private final ConcurrentHashMap<String, CachedResult> portalCache   = new ConcurrentHashMap<>();
    private volatile CachedResult                          allItemsCache = null;
    private volatile boolean                               refreshRunning = false; // evita execuções simultâneas

    private final PublisherPreviewService previewService;

    // Dois pools separados — evita deadlock pai→filho
    private final ExecutorService refreshExecutor = Executors.newSingleThreadExecutor(); // dedicado ao refreshAll
    private final ExecutorService portalExecutor  = Executors.newFixedThreadPool(10);    // 1 thread por portal
    private final ExecutorService articleExecutor = Executors.newFixedThreadPool(8);     // extração paralela de artigos

    public ScraperOrchestrator(PublisherPreviewService previewService) {
        this.previewService = previewService;
    }

    // ============================================================
    //  API PÚBLICA
    // ============================================================

    public List<NewsItem> fetchAll() {
        if (allItemsCache != null && allItemsCache.isValid()) {
            log.info("[SCRAPER] Cache HIT: " + allItemsCache.items().size() + " itens");
            return allItemsCache.items();
        }
        // Cache frio ou expirado — dispara refresh em background sem bloquear o job
        // O NewsIngestionJob vai pegar os resultados no próximo ciclo (5 min)
        if (!refreshRunning) {
            log.info("[SCRAPER] Cache frio — disparando refresh em background...");
            refreshExecutor.submit(this::refreshAll);
        } else {
            log.info("[SCRAPER] Refresh já em andamento — retornando cache atual");
        }
        return allItemsCache != null ? allItemsCache.items() : Collections.emptyList();
    }

    public List<NewsItem> fetchByPortal(String name) {
        CachedResult c = portalCache.get(name);
        if (c != null && c.isValid()) return c.items();
        PortalConfig p = findPortal(name);
        if (p != null) portalExecutor.submit(() -> processPortal(p));
        return Collections.emptyList();
    }

    public List<NewsItem> fetchByCategory(String category) {
        return fetchAll().stream()
                .filter(i -> i.category() != null && i.category().equalsIgnoreCase(category))
                .toList();
    }

    public List<String> getPortalNames() {
        return PORTALS.stream().map(PortalConfig::name).toList();
    }

    public Map<String, Object> getCacheStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        for (PortalConfig p : PORTALS) {
            CachedResult cr = portalCache.get(p.name());
            String modo = p.rssUrl() != null ? "RSS" : "Jsoup+whitelist";
            if (cr == null) {
                status.put(p.name(), Map.of("status", "nunca carregado", "modo", modo));
            } else {
                long secs = cr.isValid() ? Duration.between(Instant.now(), cr.expiresAt()).toSeconds() : 0;
                status.put(p.name(), Map.of(
                    "items", cr.items().size(), "valid", cr.isValid(),
                    "expiresInSeconds", secs, "modo", modo
                ));
            }
        }
        if (allItemsCache != null) {
            long secs = allItemsCache.isValid() ? Duration.between(Instant.now(), allItemsCache.expiresAt()).toSeconds() : 0;
            status.put("_total", Map.of("items", allItemsCache.items().size(), "valid", allItemsCache.isValid(), "expiresInSeconds", secs));
        }
        return status;
    }

    // ============================================================
    //  SCHEDULER
    // ============================================================
    @Scheduled(initialDelay = 5_000, fixedDelay = 1_200_000)
    public void scheduledRefresh() {
        log.info("[SCRAPER] Refresh agendado...");
        refreshExecutor.submit(this::refreshAll);
    }

    // ============================================================
    //  REFRESH
    // ============================================================
    private List<NewsItem> refreshAll() {
        if (refreshRunning) {
            log.info("[SCRAPER] Refresh já em andamento, ignorando chamada duplicada.");
            return allItemsCache != null ? allItemsCache.items() : Collections.emptyList();
        }
        refreshRunning = true;
        try {
        log.info("[SCRAPER] Iniciando " + PORTALS.size() + " portais...");

        List<Future<List<NewsItem>>> futures = new ArrayList<>();
        for (PortalConfig p : PORTALS) {
            futures.add(portalExecutor.submit(() -> processPortal(p)));
        }

        List<NewsItem> all = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                all.addAll(futures.get(i).get(120, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                log.warning("[SCRAPER] Timeout: " + PORTALS.get(i).name());
            } catch (Exception e) {
                log.warning("[SCRAPER] Erro " + PORTALS.get(i).name() + ": " + e.getMessage());
            }
        }

        List<NewsItem> deduped = dedup(all);
        deduped.sort(Comparator.comparing(
            (NewsItem n) -> n.publishedAt() == null ? Instant.EPOCH : n.publishedAt(),
            Comparator.reverseOrder()
        ));

        allItemsCache = new CachedResult(deduped, Instant.now().plus(CACHE_TTL));
        log.info("[SCRAPER] Refresh OK: " + deduped.size() + " únicos de " + all.size() + " totais");
        return deduped;
        } finally {
            refreshRunning = false;
        }
    }

    private List<NewsItem> processPortal(PortalConfig portal) {
        log.info("[SCRAPER] → " + portal.name() + " [" + (portal.rssUrl() != null ? "RSS" : "Jsoup+whitelist") + "]");
        List<NewsItem> items = portal.rssUrl() != null ? fetchViaRss(portal) : fetchViaJsoup(portal);
        portalCache.put(portal.name(), new CachedResult(new ArrayList<>(items), Instant.now().plus(CACHE_TTL)));
        log.info("[SCRAPER] " + portal.name() + " → " + items.size() + " itens");
        return items;
    }

    // ============================================================
    //  MODO RSS
    // ============================================================
    private List<NewsItem> fetchViaRss(PortalConfig portal) {
        try {
            log.info("[SCRAPER] RSS: " + portal.rssUrl());
            SyndFeed feed = new SyndFeedInput().build(new XmlReader(new URL(portal.rssUrl())));
            List<SyndEntry> entries = feed.getEntries();
            int count = Math.min(entries.size(), MAX_PER_PORTAL);
            log.info("[SCRAPER] " + portal.name() + ": " + entries.size() + " entries → " + count);

            List<Future<NewsItem>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final SyndEntry e = entries.get(i);
                futures.add(articleExecutor.submit(() -> {
                    humanDelay();
                    return buildFromRssEntry(e, portal);
                }));
            }

            List<NewsItem> items = new ArrayList<>();
            for (Future<NewsItem> f : futures) {
                try {
                    NewsItem item = f.get(20, TimeUnit.SECONDS);
                    if (item != null) items.add(item);
                } catch (Exception ignored) {}
            }
            return items;

        } catch (Exception e) {
            log.warning("[SCRAPER] RSS falhou " + portal.name() + ": " + e.getMessage() + " → Jsoup fallback");
            return fetchViaJsoup(portal);
        }
    }

    private NewsItem buildFromRssEntry(SyndEntry e, PortalConfig portal) {
        String title = safe(e.getTitle()).trim();
        String link  = safe(e.getLink()).trim();
        if (title.isBlank() || link.isBlank()) return null;
        title = cleanTitle(title, portal.name());

        String description = "";
        if (e.getDescription() != null) {
            String raw = Jsoup.parse(e.getDescription().getValue()).text().trim();
            if (!raw.contains(" · ") && raw.length() >= 30) description = raw;
        }

        String image = extractImageFromRssEntry(e);

        // Valida se a imagem do RSS é foto real (não logo/branding do portal)
        // Se for ruim ou null → busca og:image diretamente na página do artigo
        if (!isGoodImage(image, portal.host())) {
            image = fetchOgImage(link);
        }

        Instant publishedAt = e.getPublishedDate() != null ? e.getPublishedDate().toInstant() : Instant.now();
        String slug = generateSlug(title, link);
        return new NewsItem(slug, title, description, link, portal.name(),
                image != null ? image : "/placeholder.svg", publishedAt, portal.category());
    }

    private static String extractImageFromRssEntry(SyndEntry entry) {
        try {
            var m = (com.rometools.modules.mediarss.MediaEntryModule)
                    entry.getModule(com.rometools.modules.mediarss.MediaEntryModule.URI);
            if (m != null) {
                var md = m.getMetadata();
                if (md != null && md.getThumbnail() != null && md.getThumbnail().length > 0) {
                    var t = md.getThumbnail()[0];
                    if (t != null && t.getUrl() != null) return t.getUrl().toString();
                }
                var contents = m.getMediaContents();
                if (contents != null) for (var c : contents)
                    if (c != null && c.getReference() != null) return c.getReference().toString();
            }
            if (entry.getEnclosures() != null)
                for (SyndEnclosure enc : entry.getEnclosures())
                    if (enc != null && enc.getUrl() != null && !enc.getUrl().isBlank()) return enc.getUrl();
            if (entry.getDescription() != null) {
                var img = Jsoup.parse(entry.getDescription().getValue()).selectFirst("img[src]");
                if (img != null && img.attr("src").startsWith("http")) return img.attr("src");
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ============================================================
    //  MODO JSOUP — padrão do POC: whitelist + estrutura de URL
    // ============================================================
    private List<NewsItem> fetchViaJsoup(PortalConfig portal) {
        List<String> links = extractArticleLinksJsoup(portal);
        if (links.isEmpty()) {
            log.warning("[SCRAPER] Jsoup 0 links: " + portal.name());
            return Collections.emptyList();
        }

        log.info("[SCRAPER] " + portal.name() + " — home extraída: "
            + links.size() + " matérias encontradas. Iniciando extração individual...");

        // Loga cada matéria que vai ser processada
        for (int i = 0; i < links.size(); i++) {
            log.info("[SCRAPER] " + portal.name() + " matéria [" + (i+1) + "/" + links.size() + "]: "
                + links.get(i));
        }

        List<Future<NewsItem>> futures = new ArrayList<>();
        for (int i = 0; i < links.size(); i++) {
            final String link = links.get(i);
            final int idx = i + 1;
            futures.add(articleExecutor.submit(() -> {
                humanDelay();
                log.info("[SCRAPER] " + portal.name() + " extraindo [" + idx + "/" + links.size() + "]: "
                    + link.replaceFirst("https?://[^/]+", ""));
                NewsItem item = scrapeArticleJsoup(link, portal);
                if (item != null) {
                    int htmlSize = item.fullHtml() != null ? item.fullHtml().length() : 0;
                    log.info("[SCRAPER] " + portal.name() + " ✓ [" + idx + "] \""
                        + safe(item.title()) + "\" | html=" + htmlSize + "b");
                } else {
                    log.warning("[SCRAPER] " + portal.name() + " ✗ [" + idx + "] falhou: " + link);
                }
                return item;
            }));
        }

        List<NewsItem> items = new ArrayList<>();
        for (Future<NewsItem> f : futures) {
            try {
                NewsItem item = f.get(FETCH_TIMEOUT + 5000, TimeUnit.MILLISECONDS);
                if (item != null) items.add(item);
            } catch (Exception ignored) {}
        }

        log.info("[SCRAPER] " + portal.name() + " — concluído: "
            + items.size() + "/" + links.size() + " matérias extraídas com sucesso");
        return items;
    }

    /**
     * Extrai links da home usando a técnica do POC:
     * 1. Itera todos os <a href> do documento
     * 2. Filtra por whitelist de seções + validação estrutural do slug
     * 3. Loga: total de links, após filtro, bloqueados
     */
    private List<String> extractArticleLinksJsoup(PortalConfig portal) {
        try {
            log.info("[SCRAPER] Jsoup home: " + portal.homeUrl());

            Connection.Response res = Jsoup.connect(portal.homeUrl())
                    .userAgent(ua())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                    .header("Referer", portal.homeUrl()) // referrer = própria home (como POC)
                    .timeout(FETCH_TIMEOUT)
                    .followRedirects(true)
                    .execute();

            int status = res.statusCode();
            log.info("[SCRAPER] " + portal.name() + " home HTTP " + status);
            if (status == 403 || status == 429) {
                log.warning("[SCRAPER] BLOQUEADO " + status + ": " + portal.homeUrl());
                return Collections.emptyList();
            }

            Document doc = res.parse();
            Elements allAs = doc.select("a[href]");
            log.info("[SCRAPER] " + portal.name() + ": " + allAs.size() + " links totais na home");

            LinkedHashMap<String, String> unique = new LinkedHashMap<>();
            int blocked = 0;

            for (Element a : allAs) {
                String url   = a.absUrl("href");
                String title = a.text() == null ? "" : a.text().trim();

                if (url.isEmpty() || title.isEmpty()) continue;
                if (url.contains("#")) continue;

                String clean = url.split("\\?")[0];

                // Checa blocked paths
                boolean isBlocked = false;
                for (String b : portal.blockedPaths()) {
                    if (clean.contains(b)) { isBlocked = true; break; }
                }
                if (isBlocked) { blocked++; continue; }

                // Valida estrutura da URL (técnica do POC)
                if (!isValidArticleUrl(clean, portal)) continue;

                unique.putIfAbsent(clean, title);
                if (unique.size() >= MAX_PER_PORTAL) break;
            }

            log.info("[SCRAPER] " + portal.name() + ": " + unique.size() + " artigos válidos | " + blocked + " bloqueados por path");
            return new ArrayList<>(unique.keySet());

        } catch (Exception e) {
            log.warning("[SCRAPER] Jsoup home falhou " + portal.name() + ": " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Valida se uma URL é um artigo real usando a técnica do POC:
     * - Host correto
     * - Seção na whitelist
     * - Slug com hífen e comprimento mínimo
     * - Não é página de colunista, tag, autor, etc.
     */
    private static boolean isValidArticleUrl(String url, PortalConfig portal) {
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null || !uri.getHost().equalsIgnoreCase(portal.host())) return false;

            String path = uri.getPath() == null ? "" : uri.getPath();

            // Rejeita paths bloqueados antes de qualquer outra verificação
            for (String blocked : portal.blockedPaths()) {
                if (path.contains(blocked)) return false;
            }

            // Paths globalmente bloqueados (autopromoção, páginas institucionais)
            String pathLower = path.toLowerCase();
            for (String g : List.of("/anuncie","/sobre-","/about","/contato","/contact",
                    "/termos","/terms","/privacidade","/privacy","/institucional","/institutional",
                    "/fale-conosco","/trabalhe-conosco","/careers","/newsletter","/assine",
                    "/subscribe","/login","/cadastro","/politica-de-","/cookie","/ajuda",
                    "/help","/copyright","/expediente","/quem-somos")) {
                if (pathLower.contains(g)) return false;
            }

            String[] parts = path.split("/");
            // parts[0] = "" (antes do primeiro /), parts[1] = seção ou slug direto
            // URLs com seção: /secao/slug → parts.length >= 3
            // URLs sem seção: /slug-da-materia → parts.length == 2 (aceito se sections vazio)
            if (parts.length < 2) return false;

            String section = parts.length >= 3 ? parts[1] : "";
            String slug    = parts[parts.length - 1]; // último segmento

            if (section == null || section.isBlank()) {
                // URL sem seção — só aceita se portal não tem whitelist de seções
                if (!portal.allowedSections().isEmpty()) return false;
            }

            if (slug == null || slug.isBlank()) return false;

            // Artigos sempre têm hífens no slug (ex: "flamengo-vence-fluminense-2-0")
            if (!slug.contains("-")) return false;

            // Slugs muito curtos são seções/tags (ex: "brasil", "economia")
            if (slug.length() < 10) return false;

            // Rejeita artigos antigos — URLs com ano antes de 2025 (ex: /2022/12/lista-bilionarios)
            if (path.matches(".*/20(1\\d|2[0-4])/.*")) return false;

            // Rejeita slugs que parecem nomes de pessoas (ex: "mirelle-pinheiro", "mario-sabino")
            // Artigos têm 3+ palavras no slug ou contêm dígitos (datas, números)
            String[] words = slug.split("-");
            boolean hasDigits = slug.matches(".*\\d.*");
            // URLs sem seção (claudiodantas, forbes /ultimas-noticias/) → mínimo 3 palavras
            // URLs com seção (/politica/titulo) → mínimo 3 palavras também
            if (words.length < 3 && !hasDigits) return false;

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Scrapa um artigo individual — mesma lógica do POC fetchDetails() */
    private NewsItem scrapeArticleJsoup(String url, PortalConfig portal) {
        try {
            Connection.Response res = Jsoup.connect(url)
                    .userAgent(ua())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                    .header("Referer", portal.homeUrl()) // referer = home do portal
                    .timeout(FETCH_TIMEOUT)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .maxBodySize(3_000_000)
                    .execute();

            int status = res.statusCode();
            if (status == 403 || status == 429) {
                log.warning("[SCRAPER] BLOQUEADO " + status + " artigo: " + url);
                return null;
            }
            if (status != 200) return null;

            Document doc = res.parse();

            // Título: h1 > og:title > twitter:title > <title> (ordem do POC)
            String h1 = doc.selectFirst("h1") != null ? doc.selectFirst("h1").text().trim() : "";
            String title = firstNonBlank(
                    h1.isEmpty() ? null : h1,
                    doc.select("meta[property='og:title']").attr("content"),
                    doc.select("meta[name='twitter:title']").attr("content"),
                    doc.title()
            );
            if (title == null || title.length() < 10) return null;
            title = cleanTitle(title, portal.name());

            // Descrição: meta name=description > og:description
            String description = firstNonBlank(
                    doc.select("meta[name='description']").attr("content"),
                    doc.select("meta[property='og:description']").attr("content")
            );

            // Imagem: og:image
            String image = firstNonBlank(
                    doc.select("meta[property='og:image']").attr("content"),
                    doc.select("meta[name='twitter:image']").attr("content")
            );

            // Data: article:published_time > meta[name=date] > time[datetime]
            Instant publishedAt = parseDate(
                    doc.select("meta[property='article:published_time']").attr("content"),
                    doc.select("meta[name='date']").attr("content"),
                    doc.select("time[datetime]").attr("datetime")
            );

            // URL canônica (como o POC)
            String canonical = doc.select("link[rel=canonical]").attr("href").trim();
            if (canonical.isEmpty()) canonical = doc.select("meta[property='og:url']").attr("content").trim();
            String finalUrl = canonical.isEmpty() ? url : canonical;

            String slug = generateSlug(title, finalUrl);
            String img  = (image != null && image.startsWith("http")) ? image : "/placeholder.svg";

            // ── Captura o HTML completo do artigo para normalização posterior ──
            // O HtmlNormalizerService vai extrair o texto limpo do HTML
            // Isso garante que o texto completo chegue à IA, não só a meta description
            String fullHtml = res.body();

            return new NewsItem(slug, title,
                    description != null ? description : "",
                    finalUrl, portal.name(), img,
                    publishedAt != null ? publishedAt : Instant.now(),
                    portal.category(), fullHtml);

        } catch (Exception e) {
            String cause = e.getMessage() != null
                    ? e.getMessage().substring(0, Math.min(120, e.getMessage().length()))
                    : e.getClass().getSimpleName();
            log.warning("[SCRAPER] Artigo falhou (" + url + "): "
                    + e.getClass().getSimpleName() + " — " + cause);
            return null;
        }
    }

    /**
     * Verifica se a imagem vinda do RSS é uma foto real de notícia.
     * Rejeita logos, imagens de branding, SVGs e placeholders genéricos.
     *
     * Padrões rejeitados:
     *  - SVG de qualquer origem (são sempre logos/ícones)
     *  - CDNs de assets de portais (jsdelivr com assets-ebc, etc.)
     *  - URLs com palavras como "logo", "brand", "default", "og-default"
     *  - Imagens muito pequenas pelo nome (icon, thumb-default)
     */
    private static boolean isGoodImage(String url, String portalHost) {
        if (url == null || url.isBlank() || !url.startsWith("http")) return false;
        String s = url.toLowerCase();

        // Rejeita SVG — sempre logo/ícone
        if (s.endsWith(".svg") || s.contains(".svg?")) return false;

        // Rejeita CDN de assets genéricos
        if (s.contains("jsdelivr.net") || s.contains("assets-ebc")) return false;

        // Rejeita URLs com palavras de branding
        for (String word : List.of("logo", "brand", "og-default", "default-og",
                "icon", "favicon", "placeholder", "no-image", "sem-imagem",
                "default-image", "capa-padrao", "share-default")) {
            if (s.contains(word)) return false;
        }

        // Rejeita imagem padrão do Metrópoles (fundo vermelho com logo)
        // Ex: https://img.metropoles.com/wp-content/themes/metro/.../share.png
        if (s.contains("metropoles") && (s.contains("/themes/") || s.contains("share.png")
                || s.contains("og-metropoles") || s.contains("default"))) return false;

        return true;
    }

    /** Fetch rápido de og:image — máx 200KB (só o head) */
    private String fetchOgImage(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(ua())
                    .header("Accept-Language", "pt-BR,pt;q=0.9")
                    .timeout(5_000)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .maxBodySize(200_000)
                    .get();
            String img = firstNonBlank(
                    doc.select("meta[property='og:image']").attr("content"),
                    doc.select("meta[name='twitter:image']").attr("content")
            );
            return (img != null && img.startsWith("http")) ? img : null;
        } catch (Exception e) { return null; }
    }

    // ============================================================
    //  UTILITÁRIOS
    // ============================================================

    private static String cleanTitle(String title, String portalName) {
        if (title == null) return "";
        return title.replaceAll("\\s*[|\\-–—]\\s*" + java.util.regex.Pattern.quote(portalName) + ".*$", "").trim();
    }

    private static Instant parseDate(String... candidates) {
        for (String s : candidates) {
            if (s == null || s.isBlank()) continue;
            try { return Instant.parse(s); } catch (Exception ignored) {}
            try { return java.time.OffsetDateTime.parse(s).toInstant(); } catch (Exception ignored) {}
        }
        return null;
    }

    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) if (s != null && !s.isBlank()) return s;
        return null;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static List<NewsItem> dedup(List<NewsItem> items) {
        Map<String, NewsItem> map = new LinkedHashMap<>();
        for (NewsItem item : items) {
            if (!map.containsKey(item.slug())) {
                map.put(item.slug(), item);
            } else {
                NewsItem ex = map.get(item.slug());
                if (!item.image().startsWith("/") && ex.image().startsWith("/"))
                    map.put(item.slug(), item);
            }
        }
        return new ArrayList<>(map.values());
    }

    private static String generateSlug(String title, String url) {
        if (title == null || title.isBlank()) return shortHash(url);
        String t = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", "-")
                .replaceAll("(^-+|-+$)", "");
        String suffix = shortHash(url == null ? title : url);
        String slug = t.isBlank() ? suffix : (t + "-" + suffix);
        if (slug.length() > 90) slug = slug.substring(0, 90).replaceAll("-+$", "") + "-" + suffix;
        return slug;
    }

    private static String shortHash(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) hex.append(String.format("%02x", h[i]));
            return hex.toString();
        } catch (Exception e) { return Integer.toHexString(Objects.hashCode(s)); }
    }

    private PortalConfig findPortal(String name) {
        return PORTALS.stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }
}
