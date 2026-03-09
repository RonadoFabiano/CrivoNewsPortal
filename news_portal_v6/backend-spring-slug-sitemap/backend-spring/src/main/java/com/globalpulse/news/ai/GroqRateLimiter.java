package com.globalpulse.news.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;

/**
 * GroqRateLimiter — pool de API keys com rotação automática.
 *
 * Limites free tier Groq (llama-3.1-8b-instant):
 *   - 30 req/min por key
 *   - 14.400 tokens/min por key
 *
 * Limites usados (margem de 15%):
 *   - 25 req/min por key
 *   - 12.000 tokens/min por key
 *
 * Com 2 keys: 50 req/min e 24.000 tokens/min efetivos.
 * Summarizer usa ~3.200 tokens → ~7 chamadas/min por key → sem esperas longas.
 */
@Component
public class GroqRateLimiter {

    private static final Logger log = Logger.getLogger(GroqRateLimiter.class.getName());

    private static final int  MAX_REQ_PER_KEY    = 25;       // Groq permite 30
    private static final int  MAX_TOKENS_PER_KEY = 12_000;   // Groq permite 14.400
    private static final long WINDOW_MS          = 60_000L;

    // Intervalo mínimo entre chamadas NA MESMA KEY (anti-rajada)
    // 25 req/min = 1 a cada 2.4s. Usamos 2s — deixa espaço mas não trava.
    private static final long MIN_INTERVAL_MS = 2_000L;

    @Value("${ai.groq.apiKey}")
    private String apiKey1;

    @Value("${ai.groq.apiKey2:}")
    private String apiKey2;

    private final List<KeySlot> pool        = new ArrayList<>();
    private final Semaphore     concurrency = new Semaphore(1, true);

    @PostConstruct
    public void init() {
        pool.add(new KeySlot("KEY-1", apiKey1));
        if (apiKey2 != null && !apiKey2.isBlank()) {
            pool.add(new KeySlot("KEY-2", apiKey2));
            log.info("[RATE-LIMITER] Pool: 2 keys — " + MAX_TOKENS_PER_KEY * 2 + " tokens/min efetivos");
        } else {
            log.info("[RATE-LIMITER] Pool: 1 key — " + MAX_TOKENS_PER_KEY + " tokens/min");
        }
    }

    /** Bloqueia até haver slot livre. Retorna a API key a usar. Sempre chame release() depois. */
    public String acquire(String callerName, int estimatedTokens) throws InterruptedException {
        concurrency.acquire();
        try {
            return selectSlot(callerName, estimatedTokens);
        } catch (InterruptedException e) {
            concurrency.release();
            throw e;
        }
    }

    public String acquire(String callerName) throws InterruptedException {
        return acquire(callerName, 800);
    }

    public void release() {
        concurrency.release();
    }

    /** Penaliza uma key por 60s após receber 429 */
    public synchronized void report429(String apiKey) {
        pool.stream().filter(s -> s.key.equals(apiKey)).findFirst().ifPresent(s -> {
            s.penalizedUntil = Instant.now().toEpochMilli() + WINDOW_MS;
            log.warning("[RATE-LIMITER] " + s.name + " penalizada 60s após 429");
        });
    }

    // ─────────────────────────────────────────────────────────────────────────

    private synchronized String selectSlot(String callerName, int estimatedTokens)
            throws InterruptedException {

        while (true) {
            long now = Instant.now().toEpochMilli();
            for (KeySlot slot : pool) slot.prune(now);

            KeySlot best          = null;
            long    earliestFree  = Long.MAX_VALUE;
            String  blockReason   = "desconhecido";

            for (KeySlot slot : pool) {

                // 1. Penalizada?
                if (now < slot.penalizedUntil) {
                    trackEarliest(earliestFree, slot.penalizedUntil);
                    earliestFree = Math.min(earliestFree, slot.penalizedUntil);
                    blockReason = "penalizada";
                    continue;
                }

                // 2. Intervalo mínimo anti-rajada
                long sinceLastCall = now - slot.lastCallAt;
                if (sinceLastCall < MIN_INTERVAL_MS) {
                    long freeAt = slot.lastCallAt + MIN_INTERVAL_MS;
                    earliestFree = Math.min(earliestFree, freeAt);
                    blockReason = "intervalo-minimo";
                    continue;
                }

                // 3. Req/min esgotado?
                if (slot.reqCount() >= MAX_REQ_PER_KEY) {
                    long freeAt = slot.reqTimestamps.peekFirst() + WINDOW_MS + 200;
                    earliestFree = Math.min(earliestFree, freeAt);
                    blockReason = "req/min";
                    continue;
                }

                // 4. Tokens esgotados?
                if (slot.tokenSum() + estimatedTokens > MAX_TOKENS_PER_KEY) {
                    // Quanto tempo até a entrada mais antiga sair da janela?
                    if (!slot.tokenHistory.isEmpty()) {
                        long freeAt = slot.tokenHistory.peekFirst()[0] + WINDOW_MS + 200;
                        earliestFree = Math.min(earliestFree, freeAt);
                    } else {
                        // Sem histórico mas tokens cheios = bug — força espera curta
                        earliestFree = Math.min(earliestFree, now + 1_000);
                    }
                    blockReason = "tokens/min";
                    continue;
                }

                // Disponível — prefere a com menos tokens usados
                if (best == null || slot.tokenSum() < best.tokenSum()) {
                    best = slot;
                }
            }

            if (best != null) {
                best.reqTimestamps.addLast(now);
                best.tokenHistory.addLast(new long[]{now, estimatedTokens});
                best.lastCallAt = now;

                int totalTokens = pool.stream().mapToInt(KeySlot::tokenSum).sum();
                int totalReq    = pool.stream().mapToInt(KeySlot::reqCount).sum();

                log.info("[RATE-LIMITER] ✓ " + callerName
                    + " → " + best.name
                    + " req=" + best.reqCount() + "/" + MAX_REQ_PER_KEY
                    + " tok=" + best.tokenSum() + "/" + MAX_TOKENS_PER_KEY
                    + (pool.size() > 1
                        ? " | pool req=" + totalReq + " tok=" + totalTokens
                        : ""));
                return best.key;
            }

            // Nenhuma key disponível
            long waitMs = earliestFree == Long.MAX_VALUE
                ? 2_000L
                : Math.max(100, earliestFree - now);

            log.info("[RATE-LIMITER] ⏳ " + callerName
                + " bloqueado por [" + blockReason + "] — aguardando " + waitMs + "ms"
                + " | keys=" + pool.stream().map(s ->
                    s.name + "(req=" + s.reqCount() + " tok=" + s.tokenSum() + ")"
                ).toList());
            Thread.sleep(waitMs);
        }
    }

    private long trackEarliest(long current, long candidate) {
        return Math.min(current, candidate);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static class KeySlot {
        final String name;
        final String key;
        final Deque<Long>   reqTimestamps = new ArrayDeque<>();
        final Deque<long[]> tokenHistory  = new ArrayDeque<>();
        long lastCallAt     = 0L;
        long penalizedUntil = 0L;

        KeySlot(String name, String key) {
            this.name = name;
            this.key  = key;
        }

        void prune(long now) {
            reqTimestamps.removeIf(t -> t < now - WINDOW_MS);
            tokenHistory.removeIf(e -> e[0] < now - WINDOW_MS);
        }

        int reqCount() { return reqTimestamps.size(); }

        int tokenSum() {
            int sum = 0;
            for (long[] e : tokenHistory) sum += (int) e[1];
            return sum;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    public int poolSize() { return pool.size(); }

    public synchronized Map<String, Object> status() {
        long now = Instant.now().toEpochMilli();
        Map<String, Object> result = new LinkedHashMap<>();
        for (KeySlot slot : pool) {
            slot.prune(now);
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("req",       slot.reqCount() + "/" + MAX_REQ_PER_KEY);
            s.put("tokens",    slot.tokenSum() + "/" + MAX_TOKENS_PER_KEY);
            s.put("penalized", now < slot.penalizedUntil);
            result.put(slot.name, s);
        }
        return result;
    }
}
