package com.globalpulse.news.api;

import com.globalpulse.news.db.*;
import com.globalpulse.news.service.TrendingService;
import com.globalpulse.news.service.WeeklyDigestJob;
import com.globalpulse.news.service.ClusterService;
import com.globalpulse.news.service.RankingService;
import com.globalpulse.news.service.ExplicaService;
import com.globalpulse.news.service.RecommendationService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.cache.CacheManager;
import com.globalpulse.news.service.NewsIngestionJob;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import com.globalpulse.news.service.EntityWorker;
import com.globalpulse.news.service.MapNarrativeWorker;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
public class NewsController {

  private static final java.util.logging.Logger log =
      java.util.logging.Logger.getLogger(NewsController.class.getName());

  private final RssService                 rssService;
  private final ScraperOrchestrator        scraperOrchestrator;
  private final RawArticleRepository       rawRepo;
  private final ProcessedArticleRepository procRepo;
  private final NewsIngestionJob           ingestionJob;
  private final CacheManager               cacheManager;
  private final TrendingService            trendingService;
  private final ClusterService             clusterService;
  private final RankingService             rankingService;
  private final ExplicaService             explicaService;
  private final RecommendationService      recommendationService;
  private final WeeklyDigestJob            digestJob;
  private final MapNarrativeWorker         narrativeWorker;

  public NewsController(
      RssService rssService,
      ScraperOrchestrator scraperOrchestrator,
      RawArticleRepository rawRepo,
      ProcessedArticleRepository procRepo,
      NewsIngestionJob ingestionJob,
      CacheManager cacheManager,
      TrendingService trendingService,
      WeeklyDigestJob digestJob,
      ClusterService clusterService,
      RankingService rankingService,
      ExplicaService explicaService,
      RecommendationService recommendationService,
      MapNarrativeWorker narrativeWorker
  ) {
    this.rssService          = rssService;
    this.scraperOrchestrator = scraperOrchestrator;
    this.rawRepo             = rawRepo;
    this.procRepo            = procRepo;
    this.ingestionJob        = ingestionJob;
    this.cacheManager        = cacheManager;
    this.trendingService         = trendingService;
    this.clusterService          = clusterService;
    this.rankingService          = rankingService;
    this.explicaService          = explicaService;
    this.recommendationService   = recommendationService;
    this.digestJob               = digestJob;
    this.narrativeWorker         = narrativeWorker;
  }

  // ── Endpoints legados (cache em memória) ──────────────────────

  @GetMapping("/news")
  public ResponseEntity<List<NewsItem>> news(
      @RequestParam(required = false) String category,
      @RequestParam(defaultValue = "60") int limit,
      @RequestParam(defaultValue = "false") boolean includeScraped
  ) {
    List<NewsItem> rss = rssService.fetch(category, limit);
    List<NewsItem> result = includeScraped
        ? Stream.concat(rss.stream(), scraperOrchestrator.fetchAll().stream())
            .distinct().limit(limit).toList()
        : rss;
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(2, TimeUnit.MINUTES))
        .body(result);
  }

  @GetMapping("/news/{slug}")
  public ResponseEntity<?> newsBySlug(@PathVariable String slug) {
    return rssService.findBySlug(slug, null)
        .<ResponseEntity<?>>map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @GetMapping("/status")
  public ResponseEntity<Map<String, Object>> status() {
    Map<String, Object> result = new java.util.LinkedHashMap<>();
    result.put("rss",     rssService.getCacheStatus());
    result.put("scraper", scraperOrchestrator.getCacheStatus());
    return ResponseEntity.ok(result);
  }

  @GetMapping("/scraper/refresh")
  public ResponseEntity<Map<String, Object>> scraperRefresh() {
    new Thread(() -> scraperOrchestrator.scheduledRefresh()).start();
    return ResponseEntity.ok(Map.of(
        "message", "Refresh iniciado.",
        "portals", scraperOrchestrator.getPortalNames()
    ));
  }

  // ── Endpoints novos (banco com IA) ────────────────────────────

  /**
   * GET /api/db/news?category=Política&limit=40
   * Retorna artigos processados pelo Ollama.
   * Suporta filtro por categoria múltipla (ex: "Política" aparece em artigos
   * com aiCategories="Política,Brasil,Geral").
   */
  @Cacheable(value = "news-feed", key = "#category + ':' + #limit")
  @GetMapping("/db/news")
  public ResponseEntity<List<Map<String, Object>>> dbNews(
      @RequestParam(required = false) String category,
      @RequestParam(defaultValue = "40") int limit
  ) {
    int safe = Math.max(1, Math.min(limit, 200));

    List<ProcessedArticle> articles = (category != null && !category.isBlank()
            && !category.equalsIgnoreCase("Todos"))
        ? procRepo.findByCategory(category, PageRequest.of(0, safe))
        : procRepo.findAllReady(PageRequest.of(0, safe));

    List<Map<String, Object>> result = articles.stream().map(a -> {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("slug",        a.getSlug());
      m.put("title",       a.getAiTitle());
      m.put("description", a.getAiDescription());
      m.put("link",        a.getLink());
      m.put("source",      a.getSource());
      m.put("image",       a.getImageUrl());  // imagem original do scrape
      m.put("publishedAt", a.getPublishedAt());
      m.put("categories",  a.categoriesArray());  // array para o frontend
      m.put("category",    a.categoriesArray()[0]); // primeira = mais relevante
      return m;
    }).toList();

    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(2, TimeUnit.MINUTES).cachePublic())
        .body(result);
  }

  /**
   * GET /api/db/news/{slug}
   * Artigo individual com todos os dados (inclui rawTitle para comparação).
   */
  @Cacheable(value = "news-article", key = "#slug")
  @GetMapping("/db/news/{slug}")
  public ResponseEntity<?> dbArticle(@PathVariable String slug) {
    return procRepo.findBySlug(slug)
        .<ResponseEntity<?>>map(a -> {
          Map<String, Object> m = new LinkedHashMap<>();
          m.put("slug",        a.getSlug());
          m.put("title",       a.getAiTitle());
          m.put("description", a.getAiDescription());
          m.put("link",        a.getLink());
          m.put("source",      a.getSource());
          m.put("image",       a.getImageUrl());
          m.put("publishedAt", a.getPublishedAt());
          m.put("categories",  a.categoriesArray());
          m.put("category",    a.categoriesArray()[0]);
          m.put("processedAt", a.getProcessedAt());
          // Inclui dados originais para referência
          // rawArticleId negativo = conteúdo original CRIVO (digest), não busca no rawRepo
          if (a.getRawArticleId() != null && a.getRawArticleId() > 0) {
            rawRepo.findById(a.getRawArticleId()).ifPresent(raw -> {
              m.put("originalTitle", raw.getRawTitle());
              m.put("scrapedAt",     raw.getScrapedAt());
            });
          }
          return ResponseEntity.ok(m);
        })
        .orElse(ResponseEntity.notFound().build());
  }

  /**
   * GET /api/db/status
   * Status da fila de processamento.
   */
  @GetMapping("/db/status")
  public ResponseEntity<Map<String, Object>> dbStatus() {
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("raw_total",     rawRepo.count());
    s.put("raw_pending",   rawRepo.countByAiStatus("PENDING"));
    s.put("raw_done",      rawRepo.countByAiStatus("DONE"));
    s.put("raw_failed",    rawRepo.countByAiStatus("FAILED"));
    s.put("processed",     procRepo.count());
    return ResponseEntity.ok(s);
  }

  /**
   * GET /api/db/ingest
   * Força uma rodada de ingestão imediatamente.
   */
  @Caching(evict = {
    @CacheEvict(value = "news-feed",    allEntries = true),
    @CacheEvict(value = "sitemap",      allEntries = true)
  })
  @GetMapping("/db/ingest")
  public ResponseEntity<Map<String, Object>> dbIngest() {
    new Thread(() -> ingestionJob.ingestOnce()).start();
    return ResponseEntity.ok(Map.of(
        "message", "Ingestão iniciada. Consulte /api/db/status para acompanhar."
    ));
  }

  /**
   * GET /api/db/reset-pending
   * Reseta raw_articles para PENDING (reprocessa tudo com novo prompt).
   * NÃO apaga processed_articles existentes.
   */
  @Caching(evict = {
    @CacheEvict(value = "news-feed",    allEntries = true),
    @CacheEvict(value = "news-article", allEntries = true),
    @CacheEvict(value = "sitemap",      allEntries = true)
  })
  @GetMapping("/db/reset-pending")
  public ResponseEntity<Map<String, Object>> dbResetPending() {
    List<RawArticle> all = rawRepo.findAll();
    for (RawArticle a : all) {
      a.setAiStatus("PENDING");
      a.setAiRetries(0);
    }
    rawRepo.saveAll(all);
    return ResponseEntity.ok(Map.of(
        "message", "Todos os raw_articles resetados para PENDING",
        "count",   all.size()
    ));
  }


  // ── Páginas SEO por tag (/api/db/tag/{tag}) ──────────────────

  /**
   * GET /api/db/tag/{tag}?limit=40
   * Notícias por tag SEO slugificada (ex: "inteligencia-artificial").
   * Gera páginas /noticias/{tag}
   */
  @Cacheable(value = "news-feed", key = "'tag:' + #tag + ':' + #limit")
  @GetMapping("/db/tag/{tag}")
  public ResponseEntity<List<Map<String, Object>>> byTag(
      @PathVariable String tag,
      @RequestParam(defaultValue = "40") int limit
  ) {
    int safe = Math.max(1, Math.min(limit, 200));
    List<ProcessedArticle> articles = procRepo.findByTag(tag, PageRequest.of(0, safe));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
        .body(articles.stream().map(this::toMap).toList());
  }

  // ── Páginas de entidade ───────────────────────────────────────

  @Cacheable(value = "news-feed", key = "'country:' + #country")
  @GetMapping("/db/country/{country}")
  public ResponseEntity<List<Map<String, Object>>> byCountry(
      @PathVariable String country,
      @RequestParam(defaultValue = "40") int limit
  ) {
    return ResponseEntity.ok(
        procRepo.findByCountry(country, PageRequest.of(0, limit))
            .stream().map(this::toMap).toList());
  }

  @Cacheable(value = "news-feed", key = "'person:' + #person")
  @GetMapping("/db/person/{person}")
  public ResponseEntity<List<Map<String, Object>>> byPerson(
      @PathVariable String person,
      @RequestParam(defaultValue = "40") int limit
  ) {
    return ResponseEntity.ok(
        procRepo.findByPerson(person, person.toLowerCase().replace(" ", "-"), PageRequest.of(0, limit))
            .stream().map(this::toMap).toList());
  }

  @Cacheable(value = "news-feed", key = "'topic:' + #topic")
  @GetMapping("/db/topic/{topic}")
  public ResponseEntity<List<Map<String, Object>>> byTopic(
      @PathVariable String topic,
      @RequestParam(defaultValue = "40") int limit
  ) {
    // topic pode chegar como slug ("reforma-tributaria") ou label ("Reforma Tributária")
    // Passa ambos para a query fazer OR entre aiTags (slug) e entities (label)
    String topicLabel = topic.replace("-", " ");
    return ResponseEntity.ok(
        procRepo.findByTopic(topicLabel, topic, PageRequest.of(0, limit))
            .stream().map(this::toMap).toList());
  }

  // ── Trending topics ───────────────────────────────────────────

  /**
   * GET /api/db/trending
   * Top 12 entidades mais mencionadas nas últimas 12h.
   */
  @GetMapping("/db/trending")
  public ResponseEntity<List<Map<String, Object>>> trending() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic())
        .body(trendingService.getTrending());
  }

  // ── Status do EntityWorker ────────────────────────────────────

  @GetMapping("/db/entity-status")
  public ResponseEntity<Map<String, Object>> entityStatus() {
    long total = procRepo.count();
    long done  = procRepo.countByEntityStatus("DONE");
    long failed= procRepo.countByEntityStatus("FAILED");
    long pending = total - done - failed;
    Map<String, Object> s = new LinkedHashMap<>();
    s.put("total",   total);
    s.put("done",    done);
    s.put("failed",  failed);
    s.put("pending", pending);
    return ResponseEntity.ok(s);
  }

  /** Helper — converte ProcessedArticle para Map do frontend */
  private Map<String, Object> toMap(ProcessedArticle a) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("slug",        a.getSlug());
    m.put("title",       a.getAiTitle());
    m.put("description", a.getAiDescription());
    m.put("link",        a.getLink());
    m.put("source",      a.getSource());
    m.put("image",       a.getImageUrl());
    m.put("publishedAt", a.getPublishedAt());
    m.put("categories",  a.categoriesArray());
    m.put("category",    a.categoriesArray()[0]);
    if (a.getEntities() != null) m.put("entities", a.getEntities());
    if (a.getAiTags()   != null) m.put("tags",     a.getAiTags().split(","));
    return m;
  }


  // ── Clusters ──────────────────────────────────────────────────

  /** GET /api/db/clusters — notícias agrupadas por assunto */
  @Cacheable(value = "news-feed", key = "'clusters'")
  @GetMapping("/db/clusters")
  public ResponseEntity<List<Map<String, Object>>> clusters() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePublic())
        .body(clusterService.getClusters());
  }

  // ── Ranking ───────────────────────────────────────────────────

  /** GET /api/db/trending-news?limit=20 — notícias rankeadas por score */
  @GetMapping("/db/trending-news")
  public ResponseEntity<List<Map<String, Object>>> trendingNews(
      @RequestParam(defaultValue = "20") int limit) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
        .body(rankingService.getRanked(Math.min(limit, 100)));
  }

  /** GET /api/db/mais-lidas — alias de trending-news */
  @GetMapping("/db/mais-lidas")
  public ResponseEntity<List<Map<String, Object>>> maisLidas() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
        .body(rankingService.getRanked(20));
  }

  // ── Explica ───────────────────────────────────────────────────

  /** GET /api/db/explica/{slug} — página explicativa gerada por IA */
  @GetMapping("/db/explica/{slug}")
  public ResponseEntity<Map<String, Object>> explica(@PathVariable String slug) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(60, TimeUnit.MINUTES).cachePublic())
        .body(explicaService.explica(slug));
  }

  // ── Recomendação ──────────────────────────────────────────────

  /** GET /api/db/recommend/{slug} — artigos relacionados */
  @Cacheable(value = "news-article", key = "'rec:' + #slug")
  @GetMapping("/db/recommend/{slug}")
  public ResponseEntity<List<Map<String, Object>>> recommend(@PathVariable String slug) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(15, TimeUnit.MINUTES).cachePublic())
        .body(recommendationService.recommend(slug));
  }

  /**
   * GET /api/db/gerar-digest
   * Força geração manual do resumo semanal (útil para testar).
   */
  @GetMapping("/db/gerar-digest")
  public ResponseEntity<Map<String, Object>> gerarDigest() {
    digestJob.generate();
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("status", "ok");
    r.put("message", "Digest gerado — verifique /api/db/news para ver o resultado");
    return ResponseEntity.ok(r);
  }

  /**
   * GET /api/db/reset-entities
   * Reseta entityStatus de todos os artigos → EntityWorker reprocessa com novo prompt.
   * Útil após melhorar o prompt do OllamaEntityExtractor.
   */
  @CacheEvict(value = {"news-feed", "news-article", "sitemap"}, allEntries = true)
  @GetMapping("/db/reset-entities")
  public ResponseEntity<Map<String, Object>> resetEntities() {
    List<ProcessedArticle> all = procRepo.findAll();
    all.forEach(a -> { a.setEntityStatus(null); a.setEntities(null); a.setAiTags(null); });
    procRepo.saveAll(all);
    Map<String, Object> r = new LinkedHashMap<>();
    r.put("reset", all.size());
    r.put("message", "EntityWorker vai reprocessar todos com o novo prompt.");
    log.info("[RESET-ENTITIES] " + all.size() + " artigos resetados para reextração");
    return ResponseEntity.ok(r);
  }

  /**
   * GET /api/db/cache-stats
   * Mostra hit/miss ratio de cada cache — útil para monitorar eficiência.
   */
  @GetMapping("/db/cache-stats")
  public ResponseEntity<Map<String, Object>> cacheStats() {
    Map<String, Object> stats = new LinkedHashMap<>();
    for (String name : List.of("news-feed", "news-article", "sitemap")) {
      var cache = cacheManager.getCache(name);
      if (cache != null) {
        var nativeCache = (com.github.benmanes.caffeine.cache.Cache<?,?>) cache.getNativeCache();
        var s = nativeCache.stats();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("size",      nativeCache.estimatedSize());
        m.put("hits",      s.hitCount());
        m.put("misses",    s.missCount());
        m.put("hitRate",   String.format("%.1f%%", s.hitRate() * 100));
        m.put("evictions", s.evictionCount());
        stats.put(name, m);
      }
    }
    return ResponseEntity.ok(stats);
  }

  /**
   * GET /api/db/cache-clear
   * Limpa todos os caches manualmente (útil após deploy).
   */
  @Caching(evict = {
    @CacheEvict(value = "news-feed",    allEntries = true),
    @CacheEvict(value = "news-article", allEntries = true),
    @CacheEvict(value = "sitemap",      allEntries = true)
  })
  @GetMapping("/db/cache-clear")
  public ResponseEntity<Map<String, Object>> cacheClear() {
    return ResponseEntity.ok(Map.of("message", "Todos os caches limpos."));
  }

  @Cacheable(value = "news-feed", key = "'search:' + #q + ':' + #limit")
  @GetMapping("/db/search")
  public ResponseEntity<List<Map<String, Object>>> search(
      @RequestParam String q,
      @RequestParam(defaultValue = "30") int limit) {

    if (q == null || q.trim().length() < 2) {
      return ResponseEntity.ok(List.of());
    }

    String query = q.trim();

    // Suporte a múltiplos termos: "lula economia" → busca cada palavra
    String[] terms = query.split("\\s+");

    List<ProcessedArticle> results;

    if (terms.length == 1) {
      // Busca simples — um termo
      results = procRepo.search(query, PageRequest.of(0, limit));
    } else {
      // Múltiplos termos — intersecção: cada artigo deve conter TODOS os termos
      results = procRepo.search(terms[0], PageRequest.of(0, limit * 3));
      for (int i = 1; i < terms.length && !results.isEmpty(); i++) {
        final String term = terms[i].toLowerCase();
        results = results.stream()
          .filter(a -> {
            String haystack = (
              (a.getAiTitle()       != null ? a.getAiTitle()       : "") + " " +
              (a.getAiDescription() != null ? a.getAiDescription() : "") + " " +
              (a.getAiTags()        != null ? a.getAiTags()        : "") + " " +
              (a.getEntities()      != null ? a.getEntities()      : "") + " " +
              (a.getSource()        != null ? a.getSource()        : "")
            ).toLowerCase();
            return haystack.contains(term);
          })
          .collect(java.util.stream.Collectors.toList());
      }
      // Limita ao tamanho pedido
      if (results.size() > limit) results = results.subList(0, limit);
    }

    List<Map<String, Object>> out = results.stream().map(a -> {
      Map<String, Object> m = new java.util.LinkedHashMap<>();
      m.put("slug",        a.getSlug());
      m.put("title",       a.getAiTitle());
      m.put("description", a.getAiDescription());
      m.put("image",       a.getImageUrl());
      m.put("source",      a.getSource());
      m.put("publishedAt", a.getPublishedAt());
      m.put("categories",  a.categoriesArray());
      m.put("category",    a.categoriesArray().length > 0 ? a.categoriesArray()[0] : "Geral");
      m.put("link",        a.getLink());
      return m;
    }).collect(java.util.stream.Collectors.toList());

    return ResponseEntity.ok(out);
  }


  // ── MAPA GLOBAL ────────────────────────────────────────────────────────────

  /**
   * Normaliza aliases de países para um nome canônico.
   * Evita "EUA" e "Estados Unidos" aparecerem como dois sinais separados.
   */
  private static final Map<String, String> COUNTRY_ALIASES = new java.util.HashMap<>() {{
    put("estados unidos", "EUA");
    put("united states", "EUA");
    put("usa", "EUA");
    put("u.s.a.", "EUA");
    put("eua (estados unidos)", "EUA");
    put("america", "EUA");
    put("américa", "EUA");
    put("iran", "Irã");
    put("iram", "Irã");
    put("russia", "Rússia");
    put("rússia", "Rússia");
    put("ucrania", "Ucrânia");
    put("ukraine", "Ucrânia");
    put("india", "Índia");
    put("brasil", "Brasil");
    put("brazil", "Brasil");
    put("china", "China");
    put("israel", "Israel");
    put("turquia", "Turquia");
    put("turkey", "Turquia");
    put("franca", "França");
    put("france", "França");
    put("alemanha", "Alemanha");
    put("germany", "Alemanha");
    put("reino unido", "Reino Unido");
    put("uk", "Reino Unido");
    put("england", "Reino Unido");
    put("japao", "Japão");
    put("japan", "Japão");
    put("venezuela", "Venezuela");
    put("argentina", "Argentina");
    put("mexico", "México");
    put("siria", "Síria");
    put("syria", "Síria");
    put("libano", "Líbano");
    put("lebanon", "Líbano");
    put("arabia saudita", "Arábia Saudita");
    put("saudi arabia", "Arábia Saudita");
    put("iraque", "Iraque");
    put("iraq", "Iraque");
    put("gaza", "Gaza");
    put("palestina", "Gaza");
    put("palestine", "Gaza");
  }};

  private String normalizeCountry(String raw) {
    if (raw == null) return null;
    String key = java.text.Normalizer.normalize(raw.toLowerCase().trim(), java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
        .replaceAll("[^a-z\\s]", " ").replaceAll("\\s+", " ").trim();
    return COUNTRY_ALIASES.getOrDefault(key, raw.trim());
  }

  private static final Map<String, double[]> COUNTRY_COORDS = new java.util.LinkedHashMap<>() {{
    put("Brasil",         new double[]{-14.2, -51.9});
    put("EUA",            new double[]{37.1, -95.7});
    put("Irã",            new double[]{32.4, 53.7});
    put("Israel",         new double[]{31.0, 34.9});
    put("Rússia",         new double[]{61.5, 105.3});
    put("China",          new double[]{35.9, 104.2});
    put("Ucrânia",        new double[]{48.4, 31.2});
    put("Japão",          new double[]{36.2, 138.3});
    put("Turquia",        new double[]{38.9, 35.2});
    put("França",         new double[]{46.2, 2.2});
    put("Alemanha",       new double[]{51.2, 10.5});
    put("Reino Unido",    new double[]{55.4, -3.4});
    put("Índia",          new double[]{20.6, 78.9});
    put("Portugal",       new double[]{39.4, -8.2});
    put("México",         new double[]{23.6, -102.6});
    put("Argentina",      new double[]{-38.4, -63.6});
    put("Venezuela",      new double[]{6.4, -66.6});
    put("Colômbia",       new double[]{4.6, -74.1});
    put("Chile",          new double[]{-35.7, -71.5});
    put("Bolívia",        new double[]{-16.3, -63.6});
    put("Paraguai",       new double[]{-23.4, -58.4});
    put("Peru",           new double[]{-9.2, -75.0});
    put("Cuba",           new double[]{21.5, -79.5});
    put("Arábia Saudita", new double[]{23.9, 45.1});
    put("Iraque",         new double[]{33.2, 43.7});
    put("Síria",          new double[]{34.8, 38.9});
    put("Líbano",         new double[]{33.9, 35.5});
    put("Gaza",           new double[]{31.4, 34.3});
    put("Itália",         new double[]{41.9, 12.6});
    put("Espanha",        new double[]{40.5, -3.7});
    put("Polônia",        new double[]{51.9, 19.1});
    put("Holanda",        new double[]{52.1, 5.3});
    put("Suíça",          new double[]{46.8, 8.2});
    put("Paquistão",      new double[]{30.4, 69.3});
    put("Indonésia",      new double[]{-0.8, 113.9});
    put("Nigéria",        new double[]{9.1, 8.7});
    put("África do Sul",  new double[]{-30.6, 22.9});
    put("Coreia do Norte",new double[]{40.3, 127.5});
    put("Coreia do Sul",  new double[]{35.9, 127.8});
  }};

  /**
   * GET /api/map/signals?window=12
   * Sinais geográficos com deduplicação de aliases e conexões por coocorrência real.
   */
  @GetMapping("/map/signals")
  public ResponseEntity<Map<String, Object>> mapSignals(
      @RequestParam(defaultValue = "12") int window) {
    try {
      com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
      java.time.Instant since = java.time.Instant.now().minus(window, java.time.temporal.ChronoUnit.HOURS);
      java.time.Instant sincePrev = since.minus(window, java.time.temporal.ChronoUnit.HOURS);

      List<ProcessedArticle> recent = procRepo.findRecentAll(since).stream()
          .filter(a -> "DONE".equals(a.getEntityStatus()))
          .collect(java.util.stream.Collectors.toList());

      List<ProcessedArticle> prev = procRepo.findRecentAll(sincePrev).stream()
          .filter(a -> "DONE".equals(a.getEntityStatus()) && a.getPublishedAt().isBefore(since))
          .collect(java.util.stream.Collectors.toList());

      // ── Agrega por país normalizado ────────────────────────────
      Map<String, CountryAgg> agg = new java.util.LinkedHashMap<>();
      Map<String, Integer> prevCount = new java.util.HashMap<>();

      for (ProcessedArticle a : recent) {
        try {
          com.fasterxml.jackson.databind.JsonNode node = om.readTree(a.getEntities());
          Set<String> mentioned = new java.util.HashSet<>();
          if (node.path("countries").isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode c2 : node.path("countries")) {
              String raw = c2.asText("").trim();
              if (raw.isBlank() || raw.length() < 2) continue;
              String canonical = normalizeCountry(raw);
              if (!COUNTRY_COORDS.containsKey(canonical)) continue;
              mentioned.add(canonical);
              agg.computeIfAbsent(canonical, k -> new CountryAgg()).add(a);
            }
          }
          // Registra coocorrências (quais países aparecem juntos no mesmo artigo)
          List<String> mentionList = new java.util.ArrayList<>(mentioned);
          for (int i = 0; i < mentionList.size(); i++) {
            for (int j = i + 1; j < mentionList.size(); j++) {
              String pa = mentionList.get(i), pb = mentionList.get(j);
              agg.computeIfAbsent(pa, k -> new CountryAgg()).addCooccurrence(pb);
              agg.computeIfAbsent(pb, k -> new CountryAgg()).addCooccurrence(pa);
            }
          }
        } catch (Exception ignored) {}
      }

      for (ProcessedArticle a : prev) {
        try {
          com.fasterxml.jackson.databind.JsonNode node = om.readTree(a.getEntities());
          if (node.path("countries").isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode c2 : node.path("countries")) {
              String canonical = normalizeCountry(c2.asText("").trim());
              if (canonical != null && COUNTRY_COORDS.containsKey(canonical))
                prevCount.merge(canonical, 1, Integer::sum);
            }
          }
        } catch (Exception ignored) {}
      }

      // ── Monta sinais ───────────────────────────────────────────
      int maxScore = agg.values().stream().mapToInt(CountryAgg::score).max().orElse(1);
      List<Map<String, Object>> signals = new java.util.ArrayList<>();

      agg.entrySet().stream()
          .filter(e -> e.getValue().count >= 2 && COUNTRY_COORDS.containsKey(e.getKey()))
          .sorted(Comparator.comparingInt((Map.Entry<String, CountryAgg> e) -> e.getValue().score()).reversed())
          .limit(20)
          .forEach(e -> {
            String name = e.getKey();
            CountryAgg ca = e.getValue();
            double[] xy = COUNTRY_COORDS.get(name);

            int prevC = prevCount.getOrDefault(name, 0);
            double growth = prevC > 0 ? ((ca.count - prevC) * 100.0 / prevC) : (ca.count > 0 ? 100.0 : 0);
            String impact = ca.score() >= 20 ? "high" : ca.score() >= 10 ? "medium" : "low";
            int intensity = (int) Math.round((ca.score() * 100.0) / maxScore);

            // Manchetes com filtro: só artigos que realmente mencionam este país
            List<Map<String, Object>> headlines = ca.articles.stream()
                .limit(5)
                .map(art -> {
                  Map<String, Object> h = new java.util.LinkedHashMap<>();
                  h.put("title", art.getAiTitle() != null ? art.getAiTitle() : "");
                  h.put("source", art.getSource() != null ? art.getSource() : "");
                  h.put("slug", art.getSlug());
                  h.put("publishedAt", art.getPublishedAt() != null ? art.getPublishedAt().toString() : "");
                  h.put("category", art.categoriesArray().length > 0 ? art.categoriesArray()[0] : "Geral");
                  return h;
                })
                .collect(java.util.stream.Collectors.toList());

            // Tópicos relacionados
            Set<String> relatedTopics = new java.util.LinkedHashSet<>();
            for (ProcessedArticle art : ca.articles) {
              try {
                com.fasterxml.jackson.databind.JsonNode node = om.readTree(art.getEntities());
                node.path("topics").forEach(t -> { if (!t.asText().isBlank()) relatedTopics.add(t.asText()); });
                node.path("people").forEach(p -> { if (!p.asText().isBlank()) relatedTopics.add(p.asText()); });
              } catch (Exception ignored) {}
            }

            // Top conexões por coocorrência real
            List<Map<String, Object>> connections = ca.cooccurrences.entrySet().stream()
                .filter(ce -> ce.getValue() >= 2) // mínimo 2 artigos em comum
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(4)
                .map(ce -> {
                  Map<String, Object> conn = new java.util.LinkedHashMap<>();
                  conn.put("country", ce.getKey());
                  conn.put("weight", ce.getValue());
                  return conn;
                })
                .collect(java.util.stream.Collectors.toList());

            Map<String, Object> signal = new java.util.LinkedHashMap<>();
            signal.put("id",            EntityWorker.slugify(name));
            signal.put("name",          name);
            signal.put("lat",           xy[0]);
            signal.put("lng",           xy[1]);
            signal.put("volume",        ca.count);
            signal.put("sources",       ca.sourceCount());
            signal.put("score",         ca.score());
            signal.put("intensity",     intensity);
            signal.put("growthPct",     Math.round(growth * 10.0) / 10.0);
            signal.put("impact",        impact);
            signal.put("relatedTopics", new java.util.ArrayList<>(relatedTopics).subList(0, Math.min(6, relatedTopics.size())));
            signal.put("headlines",     headlines);
            signal.put("connections",   connections);
            signals.add(signal);
          });

      // ── Conexões globais deduplicated para o frontend ──────────
      Set<String> seen = new java.util.HashSet<>();
      List<Map<String, Object>> allConnections = new java.util.ArrayList<>();
      for (Map<String, Object> sig : signals) {
        String src = (String) sig.get("name");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conns = (List<Map<String, Object>>) sig.get("connections");
        for (Map<String, Object> conn : conns) {
          String tgt = (String) conn.get("country");
          String key = src.compareTo(tgt) < 0 ? src + "→" + tgt : tgt + "→" + src;
          if (!seen.contains(key) && COUNTRY_COORDS.containsKey(tgt)) {
            seen.add(key);
            Map<String, Object> c3 = new java.util.LinkedHashMap<>();
            c3.put("source", src);
            c3.put("target", tgt);
            c3.put("weight", conn.get("weight"));
            allConnections.add(c3);
          }
        }
      }

      Map<String, Object> result = new java.util.LinkedHashMap<>();
      result.put("signals",     signals);
      result.put("connections", allConnections);
      result.put("window",      window);
      result.put("totalArticles", recent.size());
      result.put("generatedAt", java.time.Instant.now().toString());

      return ResponseEntity.ok()
          .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
          .body(result);

    } catch (Exception e) {
      log.warning("[MAP] Erro: " + e.getMessage());
      return ResponseEntity.ok().body(Map.of("signals", List.of(), "connections", List.of(), "error", e.getMessage()));
    }
  }

  private static class CountryAgg {
    int count = 0;
    Set<String> sources = new java.util.HashSet<>();
    List<ProcessedArticle> articles = new java.util.ArrayList<>();
    Map<String, Integer> cooccurrences = new java.util.HashMap<>();

    void add(ProcessedArticle a) {
      count++;
      if (a.getSource() != null) sources.add(a.getSource());
      if (articles.size() < 6) articles.add(a);
    }
    void addCooccurrence(String other) {
      cooccurrences.merge(other, 1, Integer::sum);
    }
    int sourceCount() { return sources.size(); }
    int score() { return count + (sourceCount() * 2); }
  }

  // ── FIM MAPA GLOBAL ────────────────────────────────────────────────────────

  // ── NARRATIVAS DO MAPA ─────────────────────────────────────────────────────

  /**
   * GET /api/map/narrative
   * Retorna as análises de IA: tipo de conexão entre países + spread paths.
   * Cache de 30 minutos gerado pelo MapNarrativeWorker em background.
   */
  @GetMapping("/map/narrative")
  public ResponseEntity<Map<String, Object>> mapNarrative() {
    Map<String, Object> data = narrativeWorker.getCachedNarratives();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(15, TimeUnit.MINUTES).cachePublic())
        .body(data);
  }

  /**
   * GET /api/map/narrative/refresh
   * Força re-análise imediata (útil para testar / admin).
   */
  @GetMapping("/map/narrative/refresh")
  public ResponseEntity<Map<String, Object>> mapNarrativeRefresh() {
    new Thread(() -> narrativeWorker.analyzeNow()).start();
    return ResponseEntity.ok(Map.of("message", "Análise narrativa iniciada em background."));
  }

  // ── FIM NARRATIVAS ─────────────────────────────────────────────────────────

  /** Warm-up: pré-carrega os artigos mais recentes no cache ao iniciar */
  @org.springframework.context.event.EventListener(org.springframework.context.event.ContextRefreshedEvent.class)
  public void warmUpCache() {
    try {
      List<ProcessedArticle> recent = procRepo.findAllReady(
          org.springframework.data.domain.PageRequest.of(0, 20));
      log.info("[CACHE] Warm-up: " + recent.size() + " artigos pré-carregados");
    } catch (Exception e) {
      log.warning("[CACHE] Warm-up falhou: " + e.getMessage());
    }
  }

}