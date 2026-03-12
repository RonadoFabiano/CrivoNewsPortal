package com.globalpulse.news.ai.pool;

import com.globalpulse.news.ai.pool.KeyMetrics;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * LlmKeyPool — gerencia N API keys com:
 *  - Round Robin com preferência pela key menos usada
 *  - Backoff automático após 429
 *  - Janela deslizante de req/min e tokens/min
 *  - Métricas por key para o dashboard
 *
 * Design Patterns usados:
 *  - Object Pool Pattern: reutiliza keys sem criar objetos novos
 *  - Strategy Pattern: agnóstico ao provedor (LlmProvider)
 *  - Observer Pattern: métricas registradas a cada chamada
 */
public class LlmKeyPool {

    private static final Logger log = Logger.getLogger(LlmKeyPool.class.getName());

    private static final long WINDOW_MS       = 60_000L;
    private static final long MIN_INTERVAL_MS = 2_000L;  // anti-rajada

    private final int maxReqPerKey;
    private final int maxTokensPerKey;
    private final List<KeySlot> slots;
    private final AtomicInteger roundRobinIdx = new AtomicInteger(0);

    public LlmKeyPool(List<String> apiKeys, List<String> keyNames,
                      int maxReqPerKey, int maxTokensPerKey) {
        this.maxReqPerKey    = maxReqPerKey;
        this.maxTokensPerKey = maxTokensPerKey;
        this.slots           = new ArrayList<>();

        for (int i = 0; i < apiKeys.size(); i++) {
            String name = i < keyNames.size() ? keyNames.get(i) : "KEY-" + (i + 1);
            slots.add(new KeySlot(name, apiKeys.get(i)));
        }

        log.info("[KEY-POOL] Iniciado com " + slots.size() + " keys | "
            + "maxReq=" + maxReqPerKey + "/min | maxTok=" + maxTokensPerKey + "/min"
            + " | efetivo=" + (maxTokensPerKey * slots.size()) + " tok/min");
    }

    /**
     * Adquire a melhor key disponível.
     * Bloqueia (com sleep) até haver slot livre.
     * Sempre chame release(keyName, tokensIn, tokensOut) depois.
     */
    public synchronized KeySlot acquire(String callerName, int estimatedTokens)
            throws InterruptedException {

        while (true) {
            long now = Instant.now().toEpochMilli();
            pruneAll(now);

            KeySlot best         = null;
            long    earliestFree = Long.MAX_VALUE;
            String  blockReason  = "todas ocupadas";

            // Tenta round-robin a partir do índice atual
            int startIdx = roundRobinIdx.get() % slots.size();
            for (int i = 0; i < slots.size(); i++) {
                KeySlot slot = slots.get((startIdx + i) % slots.size());

                // 1. Penalizada por 429?
                if (now < slot.penalizedUntil) {
                    earliestFree = Math.min(earliestFree, slot.penalizedUntil);
                    blockReason = "penalizada(" + slot.name + ")";
                    continue;
                }

                // 2. Intervalo mínimo anti-rajada
                if (now - slot.lastCallAt < MIN_INTERVAL_MS) {
                    earliestFree = Math.min(earliestFree, slot.lastCallAt + MIN_INTERVAL_MS);
                    blockReason = "intervalo-minimo";
                    continue;
                }

                // 3. Req/min esgotado?
                if (slot.reqCount() >= maxReqPerKey) {
                    if (!slot.reqTimestamps.isEmpty())
                        earliestFree = Math.min(earliestFree, slot.reqTimestamps.peekFirst() + WINDOW_MS + 200);
                    blockReason = "req/min(" + slot.name + ")";
                    continue;
                }

                // 4. Tokens/min esgotados?
                if (slot.tokenSum() + estimatedTokens > maxTokensPerKey) {
                    if (!slot.tokenHistory.isEmpty())
                        earliestFree = Math.min(earliestFree, slot.tokenHistory.peekFirst()[0] + WINDOW_MS + 200);
                    blockReason = "tokens/min(" + slot.name + ")";
                    continue;
                }

                // Disponível — prefere a com menos tokens usados (load balancing)
                if (best == null || slot.tokenSum() < best.tokenSum()) {
                    best = slot;
                }
            }

            if (best != null) {
                best.reqTimestamps.addLast(now);
                best.tokenHistory.addLast(new long[]{now, estimatedTokens});
                best.lastCallAt = now;
                best.inFlight   = true;

                // Avança round-robin
                roundRobinIdx.incrementAndGet();

                int totalTok = slots.stream().mapToInt(KeySlot::tokenSum).sum();
                int totalReq = slots.stream().mapToInt(KeySlot::reqCount).sum();

                log.info("[KEY-POOL] ✓ " + callerName
                    + " → " + best.name
                    + " req=" + best.reqCount() + "/" + maxReqPerKey
                    + " tok=" + best.tokenSum() + "/" + maxTokensPerKey
                    + (slots.size() > 1 ? " | pool req=" + totalReq + " tok=" + totalTok : ""));
                return best;
            }

            long waitMs = earliestFree == Long.MAX_VALUE
                ? 2_000L : Math.max(200, earliestFree - now);

            log.info("[KEY-POOL] ⏳ " + callerName
                + " bloqueado [" + blockReason + "] — aguardando " + waitMs + "ms");
            Thread.sleep(waitMs);
        }
    }

    /** Registra resultado de uma chamada (tokens reais) */
    public synchronized void release(String keyName, int tokensIn, int tokensOut, boolean success) {
        slots.stream().filter(s -> s.name.equals(keyName)).findFirst().ifPresent(slot -> {
            slot.inFlight = false;
            if (success) {
                slot.metrics.recordCall(tokensIn, tokensOut);
            }
        });
    }

    /** Penaliza key após 429 */
    public synchronized void report429(String keyName) {
        slots.stream().filter(s -> s.name.equals(keyName)).findFirst().ifPresent(slot -> {
            slot.penalizedUntil = Instant.now().toEpochMilli() + WINDOW_MS;
            slot.metrics.record429();
            log.warning("[KEY-POOL] " + slot.name + " penalizada 60s após 429");
        });
    }

    /** Fecha ciclos de métricas (chamar a cada minuto via @Scheduled) */
    public synchronized void flushMetricsCycles() {
        slots.forEach(s -> s.metrics.flushCycle());
    }

    /** Status completo para o dashboard */
    public synchronized Map<String, Object> dashboardStatus() {
        long now = Instant.now().toEpochMilli();
        pruneAll(now);

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> keys = new ArrayList<>();

        for (KeySlot slot : slots) {
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("name",       slot.name);
            k.put("status",     now < slot.penalizedUntil ? "penalizada" : slot.inFlight ? "em-uso" : "disponivel");
            k.put("reqUsed",    slot.reqCount());
            k.put("reqLimit",   maxReqPerKey);
            k.put("tokUsed",    slot.tokenSum());
            k.put("tokLimit",   maxTokensPerKey);
            k.put("reqPct",     Math.round(slot.reqCount() * 100.0 / maxReqPerKey));
            k.put("tokPct",     Math.round(slot.tokenSum() * 100.0 / maxTokensPerKey));
            k.put("metrics",    slot.metrics.toMap());
            keys.add(k);
        }

        int totalTok = slots.stream().mapToInt(KeySlot::tokenSum).sum();
        int totalReq = slots.stream().mapToInt(KeySlot::reqCount).sum();
        long totalTokensAllTime = slots.stream()
                .mapToLong(s -> {
                    Map<String, Object> metrics = s.metrics.toMap();
                    long tokensIn = ((Number) metrics.getOrDefault("totalTokensIn", 0L)).longValue();
                    long tokensOut = ((Number) metrics.getOrDefault("totalTokensOut", 0L)).longValue();
                    return tokensIn + tokensOut;
                })
                .sum();

        result.put("keys",               keys);
        result.put("poolSize",           slots.size());
        result.put("totalReqNow",        totalReq);
        result.put("totalTokNow",        totalTok);
        result.put("totalTokensAllTime", totalTokensAllTime);
        result.put("effectiveTpmLimit",  maxTokensPerKey * slots.size());
        result.put("timestamp",          Instant.now().toString());
        return result;
    }

    public int poolSize() { return slots.size(); }

    public List<KeySlot> slots() { return Collections.unmodifiableList(slots); }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void pruneAll(long now) {
        slots.forEach(s -> s.prune(now));
    }

    // ── KeySlot ───────────────────────────────────────────────────────────────

    public static class KeySlot {
        public final String     name;
        public final String     key;
        public final KeyMetrics metrics;
        final Deque<Long>       reqTimestamps = new ArrayDeque<>();
        final Deque<long[]>     tokenHistory  = new ArrayDeque<>();
        long lastCallAt     = 0L;
        long penalizedUntil = 0L;
        boolean inFlight    = false;

        KeySlot(String name, String key) {
            this.name    = name;
            this.key     = key;
            this.metrics = new KeyMetrics(name);
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
}

