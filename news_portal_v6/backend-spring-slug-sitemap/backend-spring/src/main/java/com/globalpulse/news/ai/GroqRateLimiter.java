package com.globalpulse.news.ai;

import com.globalpulse.news.ai.pool.LlmKeyPool;
import com.globalpulse.news.ai.pool.LlmKeyPool.KeySlot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.logging.Logger;

/**
 * GroqRateLimiter v2 — suporta até 6 API keys com rotação automática.
 *
 * Design Patterns:
 *  - Facade: simplifica o uso do LlmKeyPool para o resto do sistema
 *  - Object Pool: gerencia N keys sem desperdício
 *
 * Limites Groq free tier (llama-3.3-70b-versatile):
 *   - 12.000 tokens/min por key
 *   - 25 req/min por key (margem de segurança)
 *
 * Com 6 keys: 72.000 tokens/min efetivos — sem throttling.
 */
@Component
public class GroqRateLimiter {

    private static final Logger log = Logger.getLogger(GroqRateLimiter.class.getName());

    private static final int MAX_REQ_PER_KEY    = 25;
    private static final int MAX_TOKENS_PER_KEY = 12_000;

    // Até 6 keys configuráveis no application.yml
    @Value("${ai.groq.apiKey:}")       private String key1;
    @Value("${ai.groq.apiKey2:}")      private String key2;
    @Value("${ai.groq.apiKey3:}")      private String key3;
    @Value("${ai.groq.apiKey4:}")      private String key4;
    @Value("${ai.groq.apiKey5:}")      private String key5;
    @Value("${ai.groq.apiKey6:}")      private String key6;

    private LlmKeyPool pool;

    // Guarda a key adquirida por thread para o release()
    private final ThreadLocal<KeySlot> currentSlot = new ThreadLocal<>();

    @PostConstruct
    public void init() {
        List<String> keys  = new ArrayList<>();
        List<String> names = new ArrayList<>();
        addIfPresent(keys, names, key1, "KEY-1");
        addIfPresent(keys, names, key2, "KEY-2");
        addIfPresent(keys, names, key3, "KEY-3");
        addIfPresent(keys, names, key4, "KEY-4");
        addIfPresent(keys, names, key5, "KEY-5");
        addIfPresent(keys, names, key6, "KEY-6");

        if (keys.isEmpty()) {
            log.warning("[RATE-LIMITER] Nenhuma API key configurada! Verifique ai.groq.apiKey");
            keys.add("MISSING");
            names.add("KEY-MISSING");
        }

        pool = new LlmKeyPool(keys, names, MAX_REQ_PER_KEY, MAX_TOKENS_PER_KEY);
        log.info("[RATE-LIMITER] Pool iniciado: " + keys.size() + " keys | "
            + (MAX_TOKENS_PER_KEY * keys.size()) + " tokens/min efetivos");
    }

    private void addIfPresent(List<String> keys, List<String> names, String key, String name) {
        if (key != null && !key.isBlank()) {
            keys.add(key);
            names.add(name);
        }
    }

    // ── API pública (compatível com o código existente) ───────────────────────

    /** Adquire uma key. Sempre chame release() depois. */
    public String acquire(String callerName, int estimatedTokens) throws InterruptedException {
        KeySlot slot = pool.acquire(callerName, estimatedTokens);
        currentSlot.set(slot);
        return slot.key;
    }

    public String acquire(String callerName) throws InterruptedException {
        return acquire(callerName, 800);
    }

    /** Libera a key com tokens reais (para métricas precisas) */
    public void release(int tokensIn, int tokensOut) {
        KeySlot slot = currentSlot.get();
        if (slot != null) {
            pool.release(slot.name, tokensIn, tokensOut, true);
            currentSlot.remove();
        }
    }

    /** Compatibilidade com código antigo que chama só release() */
    public void release() {
        release(0, 0);
    }

    /** Penaliza a key atual após 429 */
    public void report429(String apiKey) {
        pool.slots().stream()
            .filter(s -> s.key.equals(apiKey))
            .findFirst()
            .ifPresent(s -> pool.report429(s.name));
    }

    /** Status simplificado (compatível com código antigo) */
    public synchronized Map<String, Object> status() {
        return pool.dashboardStatus();
    }

    /** Status completo para o dashboard */
    public Map<String, Object> dashboardStatus() {
        return pool.dashboardStatus();
    }

    public int poolSize() { return pool.poolSize(); }

    /** Fecha ciclos de métricas a cada minuto */
    @Scheduled(fixedDelay = 60_000)
    public void flushMetricsCycles() {
        pool.flushMetricsCycles();
    }
}
