package com.globalpulse.news.service;

import com.globalpulse.news.ai.GroqSummarizer;
import com.globalpulse.news.ai.GroqSummarizer.AiResult;
import com.globalpulse.news.config.AppRuntimeProperties;
import com.globalpulse.news.db.ProcessedArticle;
import com.globalpulse.news.db.ProcessedArticleRepository;
import com.globalpulse.news.db.RawArticle;
import com.globalpulse.news.db.RawArticleRepository;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.logging.Logger;

@Component
public class AiSummaryWorker {

    private static final Logger log = Logger.getLogger(AiSummaryWorker.class.getName());

    private final RawArticleRepository rawRepo;
    private final ProcessedArticleRepository procRepo;
    private final GroqSummarizer summarizer;
    private final CacheManager cacheManager;
    private final AppRuntimeProperties runtimeProperties;

    public AiSummaryWorker(
            RawArticleRepository rawRepo,
            ProcessedArticleRepository procRepo,
            GroqSummarizer summarizer,
            CacheManager cacheManager,
            AppRuntimeProperties runtimeProperties
    ) {
        this.rawRepo = rawRepo;
        this.procRepo = procRepo;
        this.summarizer = summarizer;
        this.cacheManager = cacheManager;
        this.runtimeProperties = runtimeProperties;
    }

    @Scheduled(fixedDelayString = "${app.ai.worker.delayMs:5000}")
    public void runBatch() {
        if (runtimeProperties.getLab().isDisableWorkers()) return;
        if (!runtimeProperties.getAi().getWorker().isEnabled()) return;
        if (!rawRepo.existsByAiStatus("PENDING")) return;

        int batchSize = Math.max(1, runtimeProperties.getAi().getWorker().getBatchSize());
        int maxRetries = Math.max(1, runtimeProperties.getAi().getWorker().getMaxRetries());

        List<RawArticle> queue = rawRepo.findPendingQueue(PageRequest.of(0, batchSize * 3)).stream()
            .filter(a -> !"PENDING_NORMALIZE".equals(a.getNormalizeStatus()))
            .limit(batchSize)
            .toList();

        if (queue.isEmpty()) return;

        int done = 0;
        for (RawArticle raw : queue) {
            try {
                if (processOne(raw, maxRetries)) {
                    done++;
                }
            } catch (Exception e) {
                log.warning("[AI WORKER] Erro inesperado em '" + safe(raw.getRawTitle()) + "': "
                    + e.getClass().getSimpleName() + " - " + e.getMessage());
            }
        }

        if (done > 0) {
            log.info("[AI WORKER] Lote concluido: " + done + "/" + queue.size() + " artigos processados");
        }
    }

    private boolean processOne(RawArticle raw, int maxRetries) {
        if (alreadyProcessed(raw)) {
            log.info("[AI WORKER] DUPLICATA detectada, marcando DONE: " + safe(raw.getRawTitle()));
            raw.setAiStatus("DONE");
            rawRepo.save(raw);
            return true;
        }

        String baseText = pickBestText(raw);
        if (baseText == null || baseText.isBlank()) {
            log.warning("[AI WORKER] Sem texto, marcando FAILED: " + safe(raw.getRawTitle()));
            markFailed(raw);
            return true;
        }

        log.info("[AI WORKER] Processando: \"" + safe(raw.getRawTitle()) + "\""
            + " | tentativa " + (raw.getAiRetries() + 1) + "/" + maxRetries
            + " | chars=" + baseText.length()
            + " | tokens~" + (baseText.length() / 3)
            + " | portal=" + safe(raw.getSource()));

        AiResult result = summarizer.generate(raw.getRawTitle(), baseText, raw.getOriginalCategory());

        if (result == null) {
            log.warning("[AI WORKER] Groq retornou null, mantendo artigo pendente: " + safe(raw.getRawTitle()));
            return false;
        }

        if (!result.isValid()) {
            raw.setAiRetries(raw.getAiRetries() + 1);
            if (raw.getAiRetries() >= maxRetries) {
                log.warning("[AI WORKER] Max retries atingido, marcando FAILED: " + safe(raw.getRawTitle()));
                markFailed(raw);
                return true;
            }
            log.warning("[AI WORKER] Resposta invalida, artigo segue para nova tentativa ("
                + raw.getAiRetries() + "/" + maxRetries + "): " + safe(raw.getRawTitle()));
            rawRepo.save(raw);
            return false;
        }

        ProcessedArticle proc = new ProcessedArticle();
        proc.setRawArticleId(raw.getId());
        proc.setSlug(raw.getSlug());
        proc.setLink(raw.getCanonicalUrl());
        proc.setImageUrl(raw.getImageUrl());
        proc.setSource(raw.getSource());
        proc.setPublishedAt(raw.getPublishedAt());
        proc.setAiTitle(result.title());
        proc.setAiDescription(result.description());
        proc.setAiCategories(result.categories());
        procRepo.save(proc);

        raw.setAiStatus("DONE");
        rawRepo.save(raw);
        evictFeedCaches();

        log.info("[AI WORKER] DONE: \"" + safe(result.title()) + "\" | cats=" + result.categories());
        return true;
    }

    private boolean alreadyProcessed(RawArticle raw) {
        return procRepo.findByRawArticleId(raw.getId()).isPresent()
            || (raw.getSlug() != null && procRepo.findBySlug(raw.getSlug()).isPresent())
            || (raw.getCanonicalUrl() != null && procRepo.findByLink(raw.getCanonicalUrl()).isPresent());
    }

    private void markFailed(RawArticle raw) {
        raw.setAiStatus("FAILED");
        rawRepo.save(raw);

        if (procRepo.findByRawArticleId(raw.getId()).isPresent()) return;

        ProcessedArticle proc = new ProcessedArticle();
        proc.setRawArticleId(raw.getId());
        proc.setSlug(raw.getSlug());
        proc.setLink(raw.getCanonicalUrl());
        proc.setImageUrl(raw.getImageUrl());
        proc.setSource(raw.getSource());
        proc.setPublishedAt(raw.getPublishedAt());
        proc.setAiTitle(raw.getRawTitle());
        proc.setAiDescription(raw.getRawDescription() != null
            ? raw.getRawDescription().substring(0, Math.min(300, raw.getRawDescription().length()))
            : "Conteudo indisponivel.");
        proc.setAiCategories(raw.getOriginalCategory() != null
            ? raw.getOriginalCategory() + ",Geral"
            : "Geral");
        procRepo.save(proc);
    }

    private String pickBestText(RawArticle raw) {
        if (raw.getRawContentText() != null && raw.getRawContentText().length() > 200) {
            return raw.getRawContentText();
        }
        return raw.getRawDescription();
    }

    private String safe(String value) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() > 100 ? normalized.substring(0, 100) + "..." : normalized;
    }

    private void evictFeedCaches() {
        try {
            List.of("news-feed", "sitemap").forEach(name -> {
                var cache = cacheManager.getCache(name);
                if (cache != null) cache.clear();
            });
        } catch (Exception e) {
            log.fine("[AI WORKER] Cache evict falhou: " + e.getMessage());
        }
    }
}
