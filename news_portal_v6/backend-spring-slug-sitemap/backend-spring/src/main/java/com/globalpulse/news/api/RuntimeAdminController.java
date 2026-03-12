package com.globalpulse.news.api;

import com.globalpulse.news.config.AppRuntimeProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class RuntimeAdminController {

    private final AppRuntimeProperties runtimeProperties;
    private final ScraperOrchestrator scraperOrchestrator;

    public RuntimeAdminController(
            AppRuntimeProperties runtimeProperties,
            ScraperOrchestrator scraperOrchestrator
    ) {
        this.runtimeProperties = runtimeProperties;
        this.scraperOrchestrator = scraperOrchestrator;
    }

    @GetMapping("/runtime")
    public ResponseEntity<Map<String, Object>> runtime() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("lab", labConfig());
        result.put("ingestion", ingestionConfig());
        result.put("rss", rssConfig());
        result.put("scraper", scraperConfig());
        result.put("scheduling", schedulingConfig());
        result.put("schedulers", schedulers());
        return ResponseEntity.ok(result);
    }

    private Map<String, Object> labConfig() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("onlySource", runtimeProperties.getLab().getOnlySource());
        map.put("disableWorkers", runtimeProperties.getLab().isDisableWorkers());
        return map;
    }

    private Map<String, Object> ingestionConfig() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enableRss", runtimeProperties.getIngestion().isEnableRss());
        map.put("maxPerCycle", runtimeProperties.getIngestion().getMaxPerCycle());
        map.put("initialDelayMs", runtimeProperties.getIngestion().getInitialDelayMs());
        map.put("fixedDelayMs", runtimeProperties.getIngestion().getFixedDelayMs());
        return map;
    }

    private Map<String, Object> rssConfig() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", runtimeProperties.getRss().isEnabled());
        map.put("defaultFeed", runtimeProperties.getRss().getDefaultFeed());
        map.put("initialDelayMs", runtimeProperties.getRss().getInitialDelayMs());
        map.put("fixedDelayMs", runtimeProperties.getRss().getFixedDelayMs());
        map.put("categoryCacheTtlMinutes", runtimeProperties.getRss().getCategoryCacheTtlMinutes());
        return map;
    }

    private Map<String, Object> scraperConfig() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fetchTimeoutMs", runtimeProperties.getScraper().getFetchTimeoutMs());
        map.put("maxPerPortal", runtimeProperties.getScraper().getMaxPerPortal());
        map.put("fixedDelayMs", runtimeProperties.getScraper().getFixedDelayMs());
        map.put("cacheTtlMinutes", runtimeProperties.getScraper().getCacheTtlMinutes());
        map.put("configuredActivePortals", runtimeProperties.getScraper().getActivePortals());
        map.put("resolvedActivePortals", scraperOrchestrator.getPortalNames());
        return map;
    }

    private Map<String, Object> schedulingConfig() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("poolSize", runtimeProperties.getScheduling().getPoolSize());
        map.put("awaitTerminationSeconds", runtimeProperties.getScheduling().getAwaitTerminationSeconds());
        map.put("threadNamePrefix", runtimeProperties.getScheduling().getThreadNamePrefix());
        return map;
    }

    private List<Map<String, Object>> schedulers() {
        boolean workersDisabled = runtimeProperties.getLab().isDisableWorkers();
        return List.of(
                scheduler("RssService.refreshCacheScheduled", runtimeProperties.getRss().isEnabled(), "fixedDelay", runtimeProperties.getRss().getFixedDelayMs(), runtimeProperties.getRss().getInitialDelayMs(), "Refresh RSS category cache"),
                scheduler("ScraperOrchestrator.scheduledRefresh", true, "fixedDelay", runtimeProperties.getScraper().getFixedDelayMs(), null, "Refresh scraper cache for active portals"),
                scheduler("NewsIngestionJob.run", true, "fixedDelay", runtimeProperties.getIngestion().getFixedDelayMs(), runtimeProperties.getIngestion().getInitialDelayMs(), "Merge RSS and scraper into raw_article"),
                scheduler("HtmlNormalizerService.processQueue", !workersDisabled, "fixedDelay", 10000L, 20000L, "Convert raw HTML into normalized text"),
                scheduler("AiSummaryWorker.runOne", !workersDisabled && runtimeProperties.getAi().getWorker().isEnabled(), "fixedDelay", runtimeProperties.getAi().getWorker().getDelayMs(), null, "Generate processed articles with AI"),
                scheduler("EntityWorker.runOne", !workersDisabled && runtimeProperties.getAi().getEntityWorker().isEnabled(), "fixedDelay", runtimeProperties.getAi().getEntityWorker().getDelayMs(), runtimeProperties.getAi().getEntityWorker().getInitialDelayMs(), "Extract entities from processed articles (batch=" + runtimeProperties.getAi().getEntityWorker().getBatchSize() + ")"),
                scheduler("MapNarrativeWorker.run", !workersDisabled, "fixedDelay", 1800000L, 120000L, "Build narrative graph cache"),
                scheduler("WeeklyDigestJob.generateWeeklyDigest", !workersDisabled, "cron", null, null, "Generate weekly editorial digest every Monday 07:00"),
                scheduler("GroqRateLimiter.closeCycle", true, "fixedDelay", 60000L, null, "Rotate token metrics cycles")
        );
    }

    private Map<String, Object> scheduler(String name, boolean enabled, String triggerType, Long fixedDelayMs, Long initialDelayMs, String description) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("enabled", enabled);
        map.put("triggerType", triggerType);
        map.put("fixedDelayMs", fixedDelayMs);
        map.put("initialDelayMs", initialDelayMs);
        map.put("description", description);
        return map;
    }
}
