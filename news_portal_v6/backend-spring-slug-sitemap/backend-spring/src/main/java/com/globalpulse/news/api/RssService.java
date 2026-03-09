package com.globalpulse.news.api;

import com.rometools.modules.mediarss.MediaEntryModule;
import com.rometools.modules.mediarss.types.MediaContent;
import com.rometools.modules.mediarss.types.Metadata;
import com.rometools.modules.mediarss.types.Thumbnail;
import com.rometools.rome.feed.synd.*;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.logging.Logger;

@Service
@EnableScheduling
public class RssService {

    // ============================================================
    //  CACHE POR CATEGORIA — evita rebuscar RSS a cada request
    // ============================================================
    private record CategoryCache(List<NewsItem> items, Instant expiresAt) {
        boolean isValid() { return items != null && expiresAt.isAfter(Instant.now()); }
    }
    private final ConcurrentHashMap<String, CategoryCache> categoryCache = new ConcurrentHashMap<>();
    private static final Duration CATEGORY_CACHE_TTL = Duration.ofMinutes(15);

    private static final Logger log = Logger.getLogger(RssService.class.getName());

    @Value("${app.rss.defaultFeed}")
    private String defaultFeed;

    @Value("${app.site.baseUrl:http://localhost:5173}")
    private String siteBaseUrl;

    // Cache simples por slug (evita refazer fetch pra abrir a notícia)
    private final ConcurrentHashMap<String, CachedNews> slugCache = new ConcurrentHashMap<>();
    private static final Duration SLUG_CACHE_TTL = Duration.ofHours(2);

    private record CachedNews(NewsItem item, Instant expiresAt) {}

    private final PublisherPreviewService previewService;

    // Paralelismo controlado: 3 threads para Playwright não sobrecarregar
    // Cada thread terá seu próprio Playwright+Browser via ThreadLocal no PublisherPreviewService
    // Pool maior: mais notícias processadas em paralelo
    private final ExecutorService executor = Executors.newFixedThreadPool(6);

    public RssService(PublisherPreviewService previewService) {
        this.previewService = previewService;
    }

    // ============================================================
    //  FONTES RSS — dois tipos:
    //  GOOGLE  → link do item é um wrapper news.google.com (precisa Playwright para resolver)
    //  DIRECT  → link do item já é a URL final do portal (sem redirect, muito mais rápido)
    // ============================================================
    private enum SourceType { GOOGLE, DIRECT }

    private record NewsSource(String url, SourceType type, String defaultCategory) {}

    // Mapa: chave (categoria normalizada) → lista de fontes RSS
    // Múltiplas fontes por categoria = mais notícias, maior diversidade
    private static final Map<String, List<NewsSource>> SOURCES = new LinkedHashMap<>();

    static {
        // ── GERAL ──────────────────────────────────────────────────────────────
        SOURCES.put("geral", List.of(
            new NewsSource("https://news.google.com/rss?hl=pt-BR&gl=BR&ceid=BR:pt-419",
                           SourceType.GOOGLE, "Geral"),
            new NewsSource("https://agenciabrasil.ebc.com.br/rss/ultimasnoticias/feed.xml",
                           SourceType.DIRECT, "Geral"),
            new NewsSource("https://www.poder360.com.br/feed/",
                           SourceType.DIRECT, "Geral")
        ));

        // ── BRASIL ─────────────────────────────────────────────────────────────
        SOURCES.put("brasil", List.of(
            new NewsSource("https://news.google.com/rss/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNRFZxYUdjU0FtVnVLQUFQAQ?hl=pt-BR&gl=BR&ceid=BR:pt-419",
                           SourceType.GOOGLE, "Brasil"),
            new NewsSource("https://g1.globo.com/rss/g1/",
                           SourceType.DIRECT, "Brasil"),
            new NewsSource("https://feeds.folha.uol.com.br/emcimadahora/rss091.xml",
                           SourceType.DIRECT, "Brasil"),
            new NewsSource("https://agenciabrasil.ebc.com.br/rss/ultimasnoticias/feed.xml",
                           SourceType.DIRECT, "Brasil")
        ));

        // ── POLÍTICA ───────────────────────────────────────────────────────────
        SOURCES.put("politica", List.of(
            new NewsSource("https://news.google.com/rss/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNRFZxYUdjU0FtVnVLQUFQAQ?hl=pt-BR&gl=BR&ceid=BR:pt-419",
                           SourceType.GOOGLE, "Política"),
            new NewsSource("https://g1.globo.com/rss/g1/politica/",
                           SourceType.DIRECT, "Política"),
            new NewsSource("https://feeds.folha.uol.com.br/poder/rss091.xml",
                           SourceType.DIRECT, "Política"),
            new NewsSource("https://agenciabrasil.ebc.com.br/rss/politica/feed.xml",
                           SourceType.DIRECT, "Política")
        ));

        // ── MUNDO ──────────────────────────────────────────────────────────────
        SOURCES.put("mundo", List.of(
            new NewsSource("https://news.google.com/rss/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNRGx1YlY4U0FtVnVLQUFQAQ?hl=pt-BR&gl=BR&ceid=BR:pt-419",
                           SourceType.GOOGLE, "Mundo"),
            new NewsSource("https://g1.globo.com/rss/g1/mundo/",
                           SourceType.DIRECT, "Mundo"),
            new NewsSource("https://feeds.folha.uol.com.br/mundo/rss091.xml",
                           SourceType.DIRECT, "Mundo")
        ));

        // ── ECONOMIA ───────────────────────────────────────────────────────────
        SOURCES.put("economia", List.of(
            new NewsSource("https://news.google.com/rss/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNRGx6YlY4U0FtVnVLQUFQAQ?hl=pt-BR&gl=BR&ceid=BR:pt-419",
                           SourceType.GOOGLE, "Economia"),
            new NewsSource("https://g1.globo.com/rss/g1/economia/",
                           SourceType.DIRECT, "Economia"),
            new NewsSource("https://feeds.folha.uol.com.br/mercado/rss091.xml",
                           SourceType.DIRECT, "Economia"),
            new NewsSource("https://agenciabrasil.ebc.com.br/rss/economia/feed.xml",
                           SourceType.DIRECT, "Economia")
        ));

        // ── NEGÓCIOS ───────────────────────────────────────────────────────────
        SOURCES.put("negocios", List.of(
            new NewsSource("https://news.google.com/rss/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNRGx6YlY4U0FtVnVLQUFQAQ?hl=pt-BR&gl=BR&ceid=BR:pt-419",
                           SourceType.GOOGLE, "Negócios"),
            new NewsSource("https://g1.globo.com/rss/g1/economia/negocios/",
                           SourceType.DIRECT, "Negócios")
        ));

        // ── TECNOLOGIA ─────────────────────────────────────────────────────────
        SOURCES.put("tecnologia", List.of(
            new NewsSource("https://news.google.com/rss/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNRGRqY0dNU0FtVnVLQUFQAQ?hl=pt-BR&gl=BR&ceid=BR:pt-419",
                           SourceType.GOOGLE, "Tecnologia"),
            new NewsSource("https://g1.globo.com/rss/g1/tecnologia/",
                           SourceType.DIRECT, "Tecnologia"),
            new NewsSource("https://feeds.folha.uol.com.br/tec/rss091.xml",
                           SourceType.DIRECT, "Tecnologia")
        ));

        // ── ESPORTES ───────────────────────────────────────────────────────────
        SOURCES.put("esportes", List.of(
            new NewsSource("https://news.google.com/rss/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNRFp1ZEdvU0FtVnVLQUFQAQ?hl=pt-BR&gl=BR&ceid=BR:pt-419",
                           SourceType.GOOGLE, "Esportes"),
            new NewsSource("https://g1.globo.com/rss/g1/esportes/",
                           SourceType.DIRECT, "Esportes"),
            new NewsSource("https://ge.globo.com/rss/ge/",
                           SourceType.DIRECT, "Esportes")
        ));

        // ── CIÊNCIA ────────────────────────────────────────────────────────────
        SOURCES.put("ciencia", List.of(
            new NewsSource("https://news.google.com/rss/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNREp5Y0dNU0FtVnVLQUFQAQ?hl=pt-BR&gl=BR&ceid=BR:pt-419",
                           SourceType.GOOGLE, "Ciência"),
            new NewsSource("https://g1.globo.com/rss/g1/ciencia-e-saude/",
                           SourceType.DIRECT, "Ciência"),
            new NewsSource("https://agenciabrasil.ebc.com.br/rss/ciencia-e-tecnologia/feed.xml",
                           SourceType.DIRECT, "Ciência")
        ));

        // ── SAÚDE ──────────────────────────────────────────────────────────────
        SOURCES.put("saude", List.of(
            new NewsSource("https://news.google.com/rss/search?q=saude+brasil&hl=pt-BR&gl=BR&ceid=BR:pt-419",
                           SourceType.GOOGLE, "Saúde"),
            new NewsSource("https://g1.globo.com/rss/g1/bem-estar/",
                           SourceType.DIRECT, "Saúde"),
            new NewsSource("https://agenciabrasil.ebc.com.br/rss/saude/feed.xml",
                           SourceType.DIRECT, "Saúde")
        ));

        // ── ENTRETENIMENTO ─────────────────────────────────────────────────────
        SOURCES.put("entretenimento", List.of(
            new NewsSource("https://news.google.com/rss/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNREp6Y0dNU0FtVnVLQUFQAQ?hl=pt-BR&gl=BR&ceid=BR:pt-419",
                           SourceType.GOOGLE, "Entretenimento"),
            new NewsSource("https://g1.globo.com/rss/g1/pop-arte/",
                           SourceType.DIRECT, "Entretenimento")
        ));

        // ── EDUCAÇÃO ───────────────────────────────────────────────────────────
        SOURCES.put("educacao", List.of(
            new NewsSource("https://news.google.com/rss/search?q=educacao+brasil&hl=pt-BR&gl=BR&ceid=BR:pt-419",
                           SourceType.GOOGLE, "Educação"),
            new NewsSource("https://g1.globo.com/rss/g1/educacao/",
                           SourceType.DIRECT, "Educação"),
            new NewsSource("https://agenciabrasil.ebc.com.br/rss/educacao/feed.xml",
                           SourceType.DIRECT, "Educação")
        ));

        // ── JUSTIÇA ────────────────────────────────────────────────────────────
        SOURCES.put("justica", List.of(
            new NewsSource("https://news.google.com/rss/search?q=justica+stf+brasil&hl=pt-BR&gl=BR&ceid=BR:pt-419",
                           SourceType.GOOGLE, "Justiça"),
            new NewsSource("https://agenciabrasil.ebc.com.br/rss/justica/feed.xml",
                           SourceType.DIRECT, "Justiça")
        ));

        // ── CULTURA ────────────────────────────────────────────────────────────
        SOURCES.put("cultura", List.of(
            new NewsSource("https://news.google.com/rss/topics/CAAqJggKIiBDQkFTRWdvSUwyMHZNREp6Y0dNU0FtVnVLQUFQAQ?hl=pt-BR&gl=BR&ceid=BR:pt-419",
                           SourceType.GOOGLE, "Cultura"),
            new NewsSource("https://agenciabrasil.ebc.com.br/rss/cultura/feed.xml",
                           SourceType.DIRECT, "Cultura")
        ));

        // ── COTIDIANO ──────────────────────────────────────────────────────────
        SOURCES.put("cotidiano", List.of(
            new NewsSource("https://news.google.com/rss?hl=pt-BR&gl=BR&ceid=BR:pt-419",
                           SourceType.GOOGLE, "Cotidiano"),
            new NewsSource("https://feeds.folha.uol.com.br/cotidiano/rss091.xml",
                           SourceType.DIRECT, "Cotidiano")
        ));
    }

    // Aliases: variantes com acento ou grafia alternativa apontam para a mesma chave
    private static final Map<String, String> CATEGORY_ALIASES = Map.ofEntries(
        Map.entry("política",   "politica"),
        Map.entry("negócios",   "negocios"),
        Map.entry("ciência",    "ciencia"),
        Map.entry("justiça",    "justica"),
        Map.entry("educação",   "educacao"),
        Map.entry("saúde",      "saude")
    );

    /** Converte categoria recebida do frontend para chave canônica do mapa SOURCES */
    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) return "geral";

        // Remove acentos e lowercase
        String norm = java.text.Normalizer.normalize(category.trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT);
        String lower = category.trim().toLowerCase(Locale.ROOT);

        // Verifica match direto
        if (SOURCES.containsKey(lower)) return lower;
        if (SOURCES.containsKey(norm))  return norm;

        // Verifica aliases
        if (CATEGORY_ALIASES.containsKey(lower)) return CATEGORY_ALIASES.get(lower);
        if (CATEGORY_ALIASES.containsKey(norm))  return CATEGORY_ALIASES.get(norm);

        return "geral";
    }

    /** Retorna todas as chaves canônicas de categorias (sem aliases) */
    private Set<String> allCategoryKeys() {
        return SOURCES.keySet();
    }

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private final Map<String, CachedItem> cache = new ConcurrentHashMap<>();

    private record CachedItem(String finalUrl, String imageUrl, Instant savedAt) {
        boolean isExpired() { return savedAt.plus(CACHE_TTL).isBefore(Instant.now()); }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    // ============================================================
    //  PONTO DE ENTRADA
    // ============================================================
    public List<NewsItem> fetch(String category, int limit) {
        String cat = normalizeCategory(category);

        // 1) Tenta servir do cache de categoria (resposta instantânea)
        CategoryCache hit = categoryCache.get(cat);
        if (hit != null && hit.isValid()) {
            log.info("[CACHE HIT] categoria=" + cat + " items=" + hit.items().size());
            List<NewsItem> cached = hit.items();
            return limit > 0 && limit < cached.size() ? cached.subList(0, limit) : cached;
        }

        // 2) Cache miss — busca do RSS
        log.info("[CACHE MISS] buscando RSS categoria=" + cat);
        List<NewsItem> fresh = fetchFromRss(cat, limit);

        // 3) Salva no cache
        categoryCache.put(cat, new CategoryCache(new ArrayList<>(fresh), Instant.now().plus(CATEGORY_CACHE_TTL)));
        return fresh;
    }

    // ============================================================
    //  BUSCA MULTI-SOURCE — agrega todas as fontes de uma categoria
    //  e deduplica por slug antes de retornar
    // ============================================================

    /**
     * Busca RSS de TODAS as fontes da categoria, processa em paralelo,
     * deduplica por slug e ordena por data descendente.
     * Chamado apenas no cache miss ou pelo scheduler.
     */
    private List<NewsItem> fetchFromRss(String cat, int limit) {
        List<NewsSource> sources = SOURCES.getOrDefault(cat,
                List.of(new NewsSource(defaultFeed, SourceType.GOOGLE, "Geral")));

        // Coleta futures de todas as fontes em paralelo
        List<Future<List<NewsItem>>> sourceFutures = new ArrayList<>();
        for (NewsSource source : sources) {
            sourceFutures.add(executor.submit(() -> fetchSingleSource(source, cat, 20)));
        }

        // Coleta resultados de todas as fontes
        List<NewsItem> allItems = new ArrayList<>();
        for (Future<List<NewsItem>> sf : sourceFutures) {
            try {
                List<NewsItem> sourceItems = sf.get(60, TimeUnit.SECONDS);
                allItems.addAll(sourceItems);
            } catch (Exception ex) {
                log.warning("[MULTI-SOURCE] Falha em fonte de '" + cat + "': " + ex.getMessage());
            }
        }

        // Deduplica por slug (mesma notícia pode aparecer em várias fontes)
        List<NewsItem> deduped = deduplicateBySlugAndTitle(allItems);

        // Ordena do mais recente para o mais antigo
        deduped.sort(Comparator.comparing(
                (NewsItem n) -> n.publishedAt() == null ? Instant.EPOCH : n.publishedAt(),
                Comparator.reverseOrder()
        ));

        log.info("[MULTI-SOURCE] cat=" + cat + " | fontes=" + sources.size()
                + " | total=" + allItems.size() + " | deduped=" + deduped.size());

        int maxItems = Math.max(limit, 20);
        return deduped.size() > maxItems ? deduped.subList(0, maxItems) : deduped;
    }

    /**
     * Busca e processa uma única fonte RSS.
     * DIRECT: link já é a URL final → buildNewsItemDirect() (sem Playwright, ~200ms/item)
     * GOOGLE:  link é wrapper google.com → buildNewsItem() (com Playwright, ~5-10s/item)
     */
    private List<NewsItem> fetchSingleSource(NewsSource source, String cat, int maxPerSource) {
        try {
            log.info("[SOURCE] Buscando: " + source.url() + " [" + source.type() + "]");
            SyndFeed feed = new SyndFeedInput().build(new XmlReader(new URL(source.url())));
            List<SyndEntry> entries = feed.getEntries();
            int count = Math.min(entries.size(), maxPerSource);

            // Usa categoria da fonte se a chave for "geral" (ex: fonte g1-brasil → "Brasil")
            String displayCat = source.defaultCategory();

            List<Future<NewsItem>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final SyndEntry e = entries.get(i);
                futures.add(executor.submit(() ->
                        source.type() == SourceType.DIRECT
                                ? buildNewsItemDirect(e, displayCat)
                                : buildNewsItem(e, cat)
                ));
            }

            List<NewsItem> items = new ArrayList<>();
            for (Future<NewsItem> f : futures) {
                try {
                    NewsItem item = f.get(25, TimeUnit.SECONDS);
                    if (item != null) items.add(item);
                } catch (Exception ex) {
                    log.warning("[SOURCE] Timeout/erro num item de " + source.url()
                            + ": " + ex.getMessage());
                }
            }
            log.info("[SOURCE] " + source.url() + " → " + items.size() + " itens");
            return items;
        } catch (Exception ex) {
            log.warning("[SOURCE] Falha ao buscar " + source.url() + ": " + ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Constrói NewsItem para fontes DIRECT (G1, Folha, Agência Brasil…).
     * Não precisa resolver URL (já é final) nem usar Playwright para o link.
     * Tenta imagem via RSS primeiro; se não tiver, faz Jsoup rápido na página.
     * Muito mais rápido que buildNewsItem() — ~200-500ms por item.
     */
    private NewsItem buildNewsItemDirect(SyndEntry e, String displayCat) {
        String title       = safe(e.getTitle()).trim();
        String directLink  = safe(e.getLink()).trim();
        Instant publishedAt = e.getPublishedDate() != null ? e.getPublishedDate().toInstant() : null;

        // Descrição limpa
        String description = "";
        if (e.getDescription() != null) description = stripHtml(e.getDescription().getValue());

        // Detecta fonte pelo domínio do link
        String source = extractSourceFromUrl(directLink);

        // Imagem: tenta RSS primeiro, depois og:image via Jsoup (sem Playwright)
        String image = extractImageFromRssEntry(e)
                .or(() -> extractImageFromHtmlDescription(e))
                .or(() -> extractOgImageJsoup(directLink))
                .orElse("/placeholder.svg");

        String slug = generateSlug(title, directLink);
        NewsItem item = new NewsItem(slug, title, description, directLink, source, image, publishedAt, displayCat);
        putSlugCache(item);

        log.info("[DIRECT] " + title + " | img=" + (image.startsWith("/") ? "placeholder" : "ok"));
        return item;
    }

    /**
     * Extrai o nome do portal a partir da URL.
     * Ex: "https://g1.globo.com/…" → "G1"
     *     "https://feeds.folha.uol.com.br/…" → "Folha de S.Paulo"
     */
    private static String extractSourceFromUrl(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            String host = new URI(url).getHost();
            if (host == null) return "";
            if (host.contains("g1.globo") || host.contains("ge.globo"))   return "G1";
            if (host.contains("folha"))                                    return "Folha de S.Paulo";
            if (host.contains("agenciabrasil") || host.contains("ebc"))   return "Agência Brasil";
            if (host.contains("poder360"))                                 return "Poder360";
            if (host.contains("r7"))                                       return "R7";
            if (host.contains("uol"))                                      return "UOL";
            if (host.contains("estadao"))                                  return "Estadão";
            if (host.contains("cnnbrasil"))                                return "CNN Brasil";
            // fallback: usa o subdomínio mais significativo
            String[] parts = host.split("\\.");
            return parts.length >= 2
                    ? capitalize(parts[parts.length - 2])
                    : host;
        } catch (Exception e) { return ""; }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
    }

    /**
     * Remove duplicatas da lista combinada.
     * Estratégia: slug idêntico OU título muito parecido (mesmos primeiros 60 chars).
     * Em caso de duplicata, mantém o item com imagem real (não placeholder).
     */
    private List<NewsItem> deduplicateBySlugAndTitle(List<NewsItem> items) {
        Map<String, NewsItem> bySlug  = new LinkedHashMap<>();
        Map<String, NewsItem> byTitle = new LinkedHashMap<>();

        for (NewsItem item : items) {
            String slug       = item.slug();
            String titleKey   = normalizeForDedup(item.title());
            boolean hasImage  = item.image() != null && !item.image().startsWith("/");

            // Verifica duplicata por slug
            if (bySlug.containsKey(slug)) {
                NewsItem existing = bySlug.get(slug);
                // Prefere o que tem imagem real
                if (hasImage && (existing.image() == null || existing.image().startsWith("/"))) {
                    bySlug.put(slug, item);
                }
                continue;
            }

            // Verifica duplicata por título (primeiros 60 chars normalizados)
            if (byTitle.containsKey(titleKey)) {
                NewsItem existing = byTitle.get(titleKey);
                if (hasImage && (existing.image() == null || existing.image().startsWith("/"))) {
                    bySlug.remove(existing.slug());
                    bySlug.put(slug, item);
                    byTitle.put(titleKey, item);
                }
                continue;
            }

            bySlug.put(slug, item);
            byTitle.put(titleKey, item);
        }

        return new ArrayList<>(bySlug.values());
    }

    /** Normaliza título para comparação de duplicatas */
    private static String normalizeForDedup(String title) {
        if (title == null) return "";
        String t = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        return t.length() > 60 ? t.substring(0, 60) : t;
    }

    /**
     * Scheduler: renova o cache de TODAS as categorias a cada 15 minutos.
     * Inicia 5s após o boot para dar tempo ao Spring de inicializar tudo.
     */
    @Scheduled(initialDelay = 5_000, fixedDelay = 900_000)
    public void refreshCacheScheduled() {
        log.info("[SCHEDULER] Renovando cache de " + allCategoryKeys().size() + " categorias...");
        for (String cat : allCategoryKeys()) {
            try {
                List<NewsItem> items = fetchFromRss(cat, 20);
                categoryCache.put(cat, new CategoryCache(
                        new ArrayList<>(items), Instant.now().plus(CATEGORY_CACHE_TTL)));
                log.info("[SCHEDULER] " + cat + " → " + items.size() + " itens");
            } catch (Exception e) {
                log.warning("[SCHEDULER] Falha em '" + cat + "': " + e.getMessage());
            }
        }
        log.info("[SCHEDULER] Cache renovado com sucesso.");
    }

    // ============================================================
    //  CONSTRÓI UM NewsItem
    // ============================================================
    private NewsItem buildNewsItem(SyndEntry e, String cat) {
        String rawTitle   = safe(e.getTitle());
        String googleLink = safe(e.getLink());
        Instant publishedAt = e.getPublishedDate() != null ? e.getPublishedDate().toInstant() : null;

        String description = "";
        if (e.getDescription() != null) description = stripHtml(e.getDescription().getValue());

        String source = "";
        String title  = rawTitle;
        if (rawTitle.contains(" - ")) {
            String[] parts = rawTitle.split(" - ");
            if (parts.length >= 2) {
                source = parts[parts.length - 1].trim();
                title  = String.join(" - ", Arrays.copyOf(parts, parts.length - 1)).trim();
            }
        }

        // Imagem rápida do RSS (MediaRSS / enclosures / descrição HTML)
        Optional<String> rssImg = extractImageFromRssEntry(e)
                .or(() -> extractImageFromHtmlDescription(e));

        // Resolve link + imagem via cache
        CachedItem resolved = resolveAndCache(googleLink);

        String finalLink = firstNonBlank(resolved.finalUrl())
                .filter(u -> !isGoogleNewsUrl(u))
                .orElse(googleLink);

        String image = rssImg
                .or(() -> firstNonBlank(resolved.imageUrl()))
                .orElse("/placeholder.svg");

        log.info("LINK: " + googleLink + " -> " + finalLink + " | IMG: " + image);

        String slug = generateSlug(title, finalLink);

        // Capitaliza categoria para exibição (ex: "brasil" → "Brasil")
        String displayCat = cat.isEmpty() ? "Geral"
                : cat.substring(0, 1).toUpperCase(Locale.ROOT) + cat.substring(1);
        NewsItem item = new NewsItem(slug, title, description, finalLink, source, image, publishedAt, displayCat);
        putSlugCache(item);
        return item;
    }

    // ============================================================
    //  RESOLVE LINK + IMAGEM (com cache)
    // ============================================================
    private CachedItem resolveAndCache(String googleLink) {
        if (googleLink == null || googleLink.isBlank())
            return new CachedItem(null, null, Instant.now());

        CachedItem hit = cache.get(googleLink);
        if (hit != null && !hit.isExpired()) return hit;

        // Etapa 1: decode token Base64 (funciona em ~30% dos casos)
        String finalUrl = decodeGoogleToken(googleLink).orElse(null);

        // Etapa 2: redirect HTTP simples
        if (finalUrl == null || isGoogleNewsUrl(finalUrl))
            finalUrl = resolveFinalUrlByRedirect(googleLink).orElse(null);

        // Etapa 3: parse HTML Jsoup (sem JS)
        if (finalUrl == null || isGoogleNewsUrl(finalUrl))
            finalUrl = resolveViaJsoupHtml(googleLink).orElse(null);

        // Etapa 4: se temos URL real, tenta imagem via Jsoup
        if (finalUrl != null && !isGoogleNewsUrl(finalUrl)) {
            String img = extractOgImageJsoup(finalUrl).orElse(null);
            if (img != null) {
                CachedItem fresh = new CachedItem(finalUrl, img, Instant.now());
                cache.put(googleLink, fresh);
                return fresh;
            }
        }

        // Etapa 5 (principal): Playwright resolve o JS redirect do Google E extrai imagem
        // Passa a URL do Google diretamente — o Playwright vai seguir o redirect JS
        String urlParaPlaywright = (finalUrl != null && !isGoogleNewsUrl(finalUrl))
                ? finalUrl
                : googleLink;

        String[] pw = previewService.resolveUrlAndImage(urlParaPlaywright);
        String pwUrl = pw[0];
        String pwImg = pw[1];

        String bestUrl = firstNonBlank(pwUrl)
                .filter(u -> !isGoogleNewsUrl(u))
                .orElse(firstNonBlank(finalUrl).filter(u -> !isGoogleNewsUrl(u)).orElse(googleLink));

        String bestImg = firstNonBlank(pwImg)
                .or(() -> {
                    if (!isGoogleNewsUrl(bestUrl) && !bestUrl.equals(googleLink))
                        return extractOgImageJsoup(bestUrl);
                    return Optional.empty();
                })
                .orElse(null);

        CachedItem fresh = new CachedItem(bestUrl, bestImg, Instant.now());
        cache.put(googleLink, fresh);
        return fresh;
    }

    // ============================================================
    //  ESTRATÉGIA A: decodifica token Base64 do Google News
    // ============================================================
    private Optional<String> decodeGoogleToken(String url) {
        try {
            String token = extractArticlesToken(url);
            if (token == null || token.isBlank()) return Optional.empty();

            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(token));
            String s = new String(decoded, StandardCharsets.ISO_8859_1);

            Optional<String> found = extractFirstHttpUrl(s);
            if (found.isPresent()) return found;

            String cleaned = s.replaceAll("[\\u0000-\\u001F\\u007F]+", " ");
            found = extractFirstHttpUrl(cleaned);
            if (found.isPresent()) return found;

            int idx = cleaned.indexOf("https%3A%2F%2F");
            if (idx >= 0) {
                String candidate = cleaned.substring(idx).split("\\s")[0];
                String dec = java.net.URLDecoder.decode(candidate, StandardCharsets.UTF_8);
                if (isValidExternalUrl(dec)) return Optional.of(dec);
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    // ============================================================
    //  ESTRATÉGIA B: redirect HTTP
    // ============================================================
    private Optional<String> resolveFinalUrlByRedirect(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(ensureGoogleParams(url)))
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", ua())
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                    .GET().build();

            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            String finalUrl = resp.uri() != null ? resp.uri().toString() : null;
            if (isValidExternalUrl(finalUrl)) return Optional.of(finalUrl);
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    // ============================================================
    //  ESTRATÉGIA C: parse HTML Jsoup para achar link
    // ============================================================
    private Optional<String> resolveViaJsoupHtml(String url) {
        try {
            var resp = Jsoup.connect(ensureGoogleParams(url))
                    .userAgent(ua())
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                    .timeout(3000)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .ignoreContentType(true)
                    .maxBodySize(2_000_000)
                    .execute();

            Document doc = Jsoup.parse(resp.body());

            String canonical = doc.select("link[rel=canonical]").attr("href");
            if (isValidExternalUrl(canonical)) return Optional.of(canonical);

            String refresh = extractUrlFromRefresh(doc.select("meta[http-equiv=refresh]").attr("content"));
            if (isValidExternalUrl(refresh)) return Optional.of(refresh);

            for (Element a : doc.select("a[href]")) {
                String href = a.attr("abs:href");
                if (isValidExternalUrl(href)) return Optional.of(href);
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    // ============================================================
    //  og:image via Jsoup (sem JS)
    // ============================================================
    private Optional<String> extractOgImageJsoup(String url) {
        if (url == null || url.isBlank() || isGoogleNewsUrl(url)) return Optional.empty();
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(ua())
                    .header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .timeout(4000)
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .maxBodySize(3_000_000)
                    .get();

            String[] metas = {
                    "meta[property=og:image]",
                    "meta[name=twitter:image]",
                    "meta[name=twitter:image:src]",
                    "meta[itemprop=image]",
                    "link[rel=image_src]"
            };
            for (String sel : metas) {
                String attr = sel.startsWith("link") ? "href" : "content";
                String val = toAbs(doc.select(sel).attr(attr), url);
                if (isRealImageUrl(val)) return Optional.of(val);
            }

            for (Element img : doc.select("article img, main img, [class*=article] img")) {
                String src = toAbs(img.attr("src"), url);
                if (isRealImageUrl(src)) return Optional.of(src);
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    // ============================================================
    //  IMAGEM DO RSS ENTRY
    // ============================================================
    private Optional<String> extractImageFromRssEntry(SyndEntry entry) {
        try {
            MediaEntryModule m = (MediaEntryModule) entry.getModule(MediaEntryModule.URI);
            if (m != null) {
                Metadata md = m.getMetadata();
                if (md != null && md.getThumbnail() != null && md.getThumbnail().length > 0) {
                    Thumbnail t = md.getThumbnail()[0];
                    if (t != null && t.getUrl() != null) return Optional.of(t.getUrl().toString());
                }
                MediaContent[] contents = m.getMediaContents();
                if (contents != null) {
                    for (MediaContent c : contents) {
                        if (c != null && c.getReference() != null)
                            return Optional.of(c.getReference().toString());
                    }
                }
            }
            if (entry.getEnclosures() != null) {
                for (SyndEnclosure enc : entry.getEnclosures()) {
                    if (enc != null && enc.getUrl() != null && !enc.getUrl().isBlank())
                        return Optional.of(enc.getUrl());
                }
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    private Optional<String> extractImageFromHtmlDescription(SyndEntry entry) {
        try {
            if (entry.getDescription() == null) return Optional.empty();
            String html = entry.getDescription().getValue();
            if (html == null || html.isBlank()) return Optional.empty();
            Document doc = Jsoup.parse(html);
            Element img = doc.selectFirst("img[src]");
            if (img != null) {
                String src = img.attr("src");
                if (isRealImageUrl(src)) return Optional.of(src);
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    // ============================================================
    //  UTILITÁRIOS
    // ============================================================
    private static Optional<String> extractFirstHttpUrl(String blob) {
        if (blob == null || blob.isBlank()) return Optional.empty();
        int idx = blob.indexOf("https://");
        if (idx < 0) idx = blob.indexOf("http://");
        if (idx < 0) return Optional.empty();

        int end = idx;
        while (end < blob.length()) {
            char c = blob.charAt(end);
            if (c <= 0x1F || c == 0x7F || c == '"' || c == '\'' || c == ' ' || c == '<' || c == '>') break;
            end++;
        }
        String u = blob.substring(idx, end).trim();
        while (u.endsWith(".") || u.endsWith(",") || u.endsWith(")") || u.endsWith("]"))
            u = u.substring(0, u.length() - 1);
        return isValidExternalUrl(u) ? Optional.of(u) : Optional.empty();
    }

    private static String extractArticlesToken(String url) {
        int a = url.indexOf("/articles/");
        if (a < 0) return null;
        String rest = url.substring(a + "/articles/".length());
        int q = rest.indexOf('?'); if (q >= 0) rest = rest.substring(0, q);
        int h = rest.indexOf('#'); if (h >= 0) rest = rest.substring(0, h);
        return rest.trim();
    }

    private static String padBase64(String b64) {
        int mod = b64.length() % 4;
        return mod == 0 ? b64 : b64 + "====".substring(mod);
    }

    private static String toAbs(String url, String base) {
        try {
            if (url == null || url.isBlank()) return url;
            if (url.startsWith("http")) return url;
            if (url.startsWith("//")) return "https:" + url;
            return URI.create(base).resolve(url).toString();
        } catch (Exception e) { return url; }
    }

    private static Optional<String> firstNonBlank(String s) {
        return (s == null || s.isBlank()) ? Optional.empty() : Optional.of(s);
    }

    /**
     * Remove HTML e limpa descrições ruins do Google News.
     * O Google News cluster retorna descriptions como:
     * "Título da notícia BBC · G1 · CNN Brasil · UOL" — inútil para o usuário.
     * Quando isso ocorre, retornamos string vazia para o frontend mostrar só o título.
     */
    private static String stripHtml(String html) {
        if (html == null || html.isBlank()) return "";
        try {
            String text = Jsoup.parse(html).text().trim();
            // Descarta se parece um cluster do Google News:
            // - Contém " · " (separador de fontes do Google News)
            // - É uma lista de fontes sem conteúdo informativo
            if (text.contains(" · ")) return "";
            // Descarta se for muito curto (menos de 30 chars) — não é uma descrição útil
            if (text.length() < 30) return "";
            return text;
        } catch (Exception e) { return ""; }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String ua() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    }

    private static String ensureGoogleParams(String url) {
        if (url == null) return null;
        if (url.contains("hl=") && url.contains("gl=") && url.contains("ceid=")) return url;
        return url + (url.contains("?") ? "&" : "?") + "hl=pt-BR&gl=BR&ceid=BR:pt-419";
    }

    private static String extractUrlFromRefresh(String content) {
        if (content == null) return null;
        int idx = content.toLowerCase(Locale.ROOT).indexOf("url=");
        if (idx < 0) return null;
        String u = content.substring(idx + 4).trim();
        if (u.length() > 1 && (u.charAt(0) == '\'' || u.charAt(0) == '"'))
            u = u.substring(1, u.length() - 1);
        return u.trim();
    }

    private static boolean isGoogleNewsUrl(String u) {
        if (u == null) return false;
        String s = u.toLowerCase(Locale.ROOT);
        return s.contains("news.google.com") || s.contains("google.com/news");
    }

    private static boolean isValidExternalUrl(String u) {
        if (u == null || u.isBlank() || !u.startsWith("http")) return false;
        String s = u.toLowerCase(Locale.ROOT);
        return !s.contains("google.com") && !s.contains("gstatic.com") && !s.contains("googleusercontent.com");
    }

    private static boolean isRealImageUrl(String u) {
        if (u == null || u.isBlank()) return false;
        String s = u.toLowerCase(Locale.ROOT);
        if (!(s.startsWith("http://") || s.startsWith("https://"))) return false;
        if (s.contains("google.com") || s.contains("gstatic.com") || s.contains("googleusercontent.com")) return false;
        // Rejeita SVGs — sempre logos/ícones
        if (s.endsWith(".svg") || s.contains(".svg?") || s.contains("/svg/")) return false;
        // Rejeita logos da Agência Brasil
        if (s.contains("assets-ebc") || s.contains("logo-agenciabrasil")) return false;
        // Rejeita imagem padrão do Metrópoles (fundo vermelho com logo)
        if (s.contains("metropoles") && (s.contains("/themes/") || s.contains("share.png")
                || s.contains("og-metropoles") || s.contains("-default"))) return false;
        // Rejeita qualquer imagem com palavras de branding
        for (String word : new String[]{"logo", "brand", "og-default", "default-og",
                "favicon", "no-image", "share-default", "capa-padrao"}) {
            if (s.contains(word)) return false;
        }
        return true;
    }


// ============================================================
//  SLUG + CACHE + SITEMAP
// ============================================================
private void putSlugCache(NewsItem item) {
    if (item == null || item.slug() == null || item.slug().isBlank()) return;
    slugCache.put(item.slug(), new CachedNews(item, Instant.now().plus(SLUG_CACHE_TTL)));
}

public Optional<NewsItem> findBySlug(String slug, String category) {
    if (slug == null || slug.isBlank()) return Optional.empty();

    CachedNews cached = slugCache.get(slug);
    if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
        return Optional.ofNullable(cached.item());
    }

    // Recarrega a categoria (até 60) para repopular o cache e tentar achar
    try {
        fetch(category, 60);
    } catch (Exception ignored) {}

    CachedNews cached2 = slugCache.get(slug);
    if (cached2 != null && cached2.expiresAt().isAfter(Instant.now())) {
        return Optional.ofNullable(cached2.item());
    }
    return Optional.empty();
}

public String buildSitemapXml() {
    // Garante ao menos "geral" no cache
    if (slugCache.isEmpty()) {
        try { fetch("geral", 60); } catch (Exception ignored) {}
    }

    String base = (siteBaseUrl == null || siteBaseUrl.isBlank())
            ? "http://localhost:5173"
            : siteBaseUrl.replaceAll("/+$", "");

    StringBuilder sb = new StringBuilder();
    sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

    // Ordena por publishedAt desc (se existir)
    List<NewsItem> items = slugCache.values().stream()
            .filter(c -> c.expiresAt().isAfter(Instant.now()))
            .map(CachedNews::item)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing((NewsItem n) -> n.publishedAt() == null ? Instant.EPOCH : n.publishedAt()).reversed())
            .limit(300)
            .toList();

    for (NewsItem n : items) {
        String loc = base + "/noticia/" + n.slug();
        sb.append("  <url>\n");
        sb.append("    <loc>").append(escapeXml(loc)).append("</loc>\n");
        if (n.publishedAt() != null) {
            sb.append("    <lastmod>").append(n.publishedAt().toString()).append("</lastmod>\n");
        }
        sb.append("  </url>\n");
    }

    sb.append("</urlset>");
    return sb.toString();
}

private String generateSlug(String title, String link) {
    if (title == null || title.isBlank()) return shortHash(link == null ? "" : link);

    // 1) Normaliza NFD para separar letras de diacríticos (acentos)
    String t = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD);
    // 2) Remove os diacríticos (acentos, cedilha, til, etc.)
    t = t.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    // 3) Minúsculas
    t = t.toLowerCase(Locale.ROOT);
    // 4) Mantém apenas a-z, 0-9 e espaços
    t = t.replaceAll("[^a-z0-9\\s]", " ");
    // 5) Colapsa espaços em hífen e remove hífens nas bordas
    t = t.replaceAll("\\s+", "-").replaceAll("(^-+|-+$)", "");

    String suffix = shortHash(link == null ? title : link);
    String slug = t.isBlank() ? suffix : (t + "-" + suffix);
    // 6) Limita a 90 caracteres preservando o sufixo de hash
    if (slug.length() > 90) slug = slug.substring(0, 90).replaceAll("-+$", "") + "-" + suffix;
    return slug;
}

private String shortHash(String s) {
    try {
        var md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(String.valueOf(s).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < 4; i++) { // 8 hex chars
            hex.append(String.format("%02x", h[i]));
        }
        return hex.toString();
    } catch (Exception e) {
        return Integer.toHexString(Objects.hashCode(s));
    }
}

private String escapeXml(String s) {
    return s == null ? "" : s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
}

public Map<String, Object> getCacheStatus() {
    Map<String, Object> status = new java.util.LinkedHashMap<>();
    categoryCache.forEach((cat, cc) -> {
        long secondsLeft = cc.isValid()
                ? Duration.between(Instant.now(), cc.expiresAt()).toSeconds()
                : 0;
        status.put(cat, Map.of(
                "items", cc.items() != null ? cc.items().size() : 0,
                "valid", cc.isValid(),
                "expiresInSeconds", secondsLeft
        ));
    });
    status.put("slugCacheSize", slugCache.size());
    status.put("urlCacheSize", cache.size());
    return status;
}

}
