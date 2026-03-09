package com.globalpulse.news.service;

import com.globalpulse.news.ai.GroqSummarizer;
import com.globalpulse.news.ai.GroqSummarizer.AiResult;
import com.globalpulse.news.db.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.logging.Logger;

/**
 * Worker da fila de IA — processa 1 artigo por vez.
 *
 * Fluxo:
 *  1. Pega 1 RawArticle com aiStatus=PENDING da fila
 *  2. Usa rawContentText (texto completo) como contexto para o Ollama
 *  3. Groq gera: título atraente + descrição envolvente + categorias múltiplas
 *  4. Salva resultado em ProcessedArticle (tabela separada)
 *  5. Copia imageUrl original do scrape → nunca alterada
 *  6. Marca RawArticle como DONE
 */
@Component
public class AiSummaryWorker {

    private final CacheManager cacheManager;

    private static final Logger log = Logger.getLogger(AiSummaryWorker.class.getName());

    private final RawArticleRepository       rawRepo;
    private final ProcessedArticleRepository procRepo;
    private final GroqSummarizer             summarizer;

    @Value("${app.ai.worker.enabled:true}")   private boolean enabled;
    @Value("${app.ai.worker.delayMs:15000}")  private long    delayMs;
    @Value("${app.ai.worker.maxChars:6000}")  private int     maxChars;
    @Value("${app.ai.worker.maxRetries:3}")   private int     maxRetries;

    public AiSummaryWorker(
            RawArticleRepository rawRepo,
            ProcessedArticleRepository procRepo,
            GroqSummarizer summarizer,
            CacheManager cacheManager
    ) {
        this.rawRepo      = rawRepo;
        this.procRepo     = procRepo;
        this.summarizer   = summarizer;
        this.cacheManager = cacheManager;
    }

    @Scheduled(fixedDelayString = "${app.ai.worker.delayMs:15000}")
    public void runOne() {
        if (!enabled) return;
        if (!rawRepo.existsByAiStatus("PENDING")) return;

        // Só processa artigos que já foram normalizados (HTML já foi limpo)
        List<RawArticle> queue = rawRepo.findPendingQueue(PageRequest.of(0, 1))
            .stream()
            .filter(a -> !"PENDING_NORMALIZE".equals(a.getNormalizeStatus()))
            .toList();
        if (queue.isEmpty()) return;

        RawArticle raw = queue.get(0);

        // ── ANTI-DUPLICATA ── verificação prévia por slug E por URL canônica
        boolean jaExiste = procRepo.findByRawArticleId(raw.getId()).isPresent()
            || (raw.getSlug()         != null && procRepo.findBySlug(raw.getSlug()).isPresent())
            || (raw.getCanonicalUrl() != null && procRepo.findByLink(raw.getCanonicalUrl()).isPresent());

        if (jaExiste) {
            log.info("[AI WORKER] DUPLICATA detectada antes do Groq — marcando DONE sem reprocessar: "
                    + safe(raw.getRawTitle()));
            raw.setAiStatus("DONE");
            rawRepo.save(raw);
            return;
        }

        // Usa texto completo — sem truncamento
        String baseText = pickBestText(raw);

        if (baseText == null || baseText.isBlank()) {
            log.warning("[AI WORKER] Sem texto → FAILED: " + safe(raw.getRawTitle()));
            markFailed(raw);
            return;
        }

        log.info("[AI WORKER] Processando: \"" + safe(raw.getRawTitle()) + "\""
                + " | tentativa " + (raw.getAiRetries() + 1) + "/" + maxRetries
                + " | chars=" + baseText.length()
                + " | tokens≈" + (baseText.length() / 3)
                + " | portal=" + raw.getSource());

        // Gera título + descrição + categorias
        AiResult result = summarizer.generate(
                raw.getRawTitle(),
                baseText,
                raw.getOriginalCategory()
        );

        if (result == null || !result.isValid()) {
            if (result == null) {
                log.warning("[AI WORKER] Groq retornou null — artigo fica PENDING para próxima tentativa");
                return;
            }
            raw.setAiRetries(raw.getAiRetries() + 1);
            if (raw.getAiRetries() >= maxRetries) {
                log.warning("[AI WORKER] Max retries → FAILED: " + safe(raw.getRawTitle()));
                markFailed(raw);
            } else {
                log.warning("[AI WORKER] Inválido, retentando depois ("
                        + raw.getAiRetries() + "/" + maxRetries + ")");
                rawRepo.save(raw);
            }
            return;
        }

        // Salva em processed_article
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

        // Invalida cache para o novo artigo aparecer imediatamente no feed
        evictFeedCaches();

        log.info("[AI WORKER] DONE → \"" + safe(result.title())
                + "\" | cats=" + result.categories());
    }

    private void markFailed(RawArticle raw) {
        raw.setAiStatus("FAILED");
        rawRepo.save(raw);

        // Mesmo em FAILED, cria um processed com dados originais
        // para o frontend não ficar vazio
        if (procRepo.findByRawArticleId(raw.getId()).isEmpty()) {
            ProcessedArticle proc = new ProcessedArticle();
            proc.setRawArticleId(raw.getId());
            proc.setSlug(raw.getSlug());
            proc.setLink(raw.getCanonicalUrl());
            proc.setImageUrl(raw.getImageUrl());
            proc.setSource(raw.getSource());
            proc.setPublishedAt(raw.getPublishedAt());
            proc.setAiTitle(raw.getRawTitle());  // usa título original
            proc.setAiDescription(raw.getRawDescription() != null
                    ? raw.getRawDescription().substring(0,
                        Math.min(300, raw.getRawDescription().length()))
                    : "Conteúdo indisponível.");
            proc.setAiCategories(raw.getOriginalCategory() != null
                    ? raw.getOriginalCategory() + ",Geral" : "Geral");
            procRepo.save(proc);
        }
    }

    /** Prefere texto completo (rawContentText), cai em rawDescription */
    private String pickBestText(RawArticle raw) {
        if (raw.getRawContentText() != null && raw.getRawContentText().length() > 200)
            return raw.getRawContentText();
        return raw.getRawDescription();
    }

    private String safe(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }

    /** Invalida caches do feed e sitemap após novo artigo processado */
    private void evictFeedCaches() {
        try {
            List.of("news-feed", "sitemap").forEach(name -> {
                var cache = cacheManager.getCache(name);
                if (cache != null) cache.clear();
            });
        } catch (Exception e) {
            log.fine("[AI-WORKER] Cache evict falhou: " + e.getMessage());
        }
    }
}
