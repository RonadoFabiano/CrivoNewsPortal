package com.globalpulse.news.ai;

import com.globalpulse.news.ai.pool.LlmKeyPool;
import com.globalpulse.news.ai.pool.LlmKeyPool.KeySlot;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@Component
public class GroqRateLimiter {

    private static final Logger log = Logger.getLogger(GroqRateLimiter.class.getName());

    private record Lease(KeySlot slot, long acquiredAt, int estimatedTokens) {}

    private static final int MAX_REQ_PER_KEY = 25;
    private static final int MAX_TOKENS_PER_KEY = 12_000;

    @Value("${ai.groq.apiKey:}")  private String key1;
    @Value("${ai.groq.apiKey2:}") private String key2;
    @Value("${ai.groq.apiKey3:}") private String key3;
    @Value("${ai.groq.apiKey4:}") private String key4;
    @Value("${ai.groq.apiKey5:}") private String key5;
    @Value("${ai.groq.apiKey6:}") private String key6;

    private LlmKeyPool pool;
    private final ThreadLocal<Lease> currentLease = new ThreadLocal<>();

    @PostConstruct
    public void init() {
        List<String> keys = new ArrayList<>();
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
        log.info("[RATE-LIMITER] Keys carregadas: " + String.join(", ", names));
        log.info("[RATE-LIMITER] Pool iniciado: " + keys.size() + " keys | "
            + (MAX_TOKENS_PER_KEY * keys.size()) + " tokens/min efetivos");
    }

    private void addIfPresent(List<String> keys, List<String> names, String key, String name) {
        if (key != null && !key.isBlank()) {
            keys.add(key);
            names.add(name);
        }
    }

    public String acquire(String callerName, int estimatedTokens) throws InterruptedException {
        KeySlot slot = pool.acquire(callerName, estimatedTokens);
        currentLease.set(new Lease(slot, slot.reservationTimestamp(), estimatedTokens));
        return slot.key;
    }

    public String acquire(String callerName) throws InterruptedException {
        return acquire(callerName, 800);
    }

    public void release(int tokensIn, int tokensOut) {
        Lease lease = currentLease.get();
        if (lease != null) {
            pool.release(
                lease.slot().name,
                lease.acquiredAt(),
                lease.estimatedTokens(),
                tokensIn,
                tokensOut,
                true
            );
            currentLease.remove();
        }
    }

    public void release() {
        release(0, 0);
    }

    public void report429(String apiKey) {
        pool.slots().stream()
            .filter(s -> s.key.equals(apiKey))
            .findFirst()
            .ifPresent(s -> pool.report429(s.name));
    }

    public synchronized Map<String, Object> status() {
        return pool.dashboardStatus();
    }

    public Map<String, Object> dashboardStatus() {
        return pool.dashboardStatus();
    }

    public int poolSize() {
        return pool.poolSize();
    }

    @Scheduled(fixedDelay = 60_000)
    public void flushMetricsCycles() {
        pool.flushMetricsCycles();
    }
}
