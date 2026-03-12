package com.globalpulse.news.ai.pool;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * LlmKeyPool - manages multiple API keys with soft rotation before hard throttling.
 */
public class LlmKeyPool {

    private static final Logger log = Logger.getLogger(LlmKeyPool.class.getName());

    private static final long WINDOW_MS = 60_000L;
    private static final long MIN_INTERVAL_MS = 2_000L;
    private static final double SOFT_REQ_THRESHOLD = 0.80;
    private static final double SOFT_TOKEN_THRESHOLD = 0.80;
    private static final long SOFT_COOLDOWN_MS = 15_000L;

    private final int maxReqPerKey;
    private final int maxTokensPerKey;
    private final List<KeySlot> slots;
    private final AtomicInteger roundRobinIdx = new AtomicInteger(0);

    public LlmKeyPool(List<String> apiKeys, List<String> keyNames,
                      int maxReqPerKey, int maxTokensPerKey) {
        this.maxReqPerKey = maxReqPerKey;
        this.maxTokensPerKey = maxTokensPerKey;
        this.slots = new ArrayList<>();

        for (int i = 0; i < apiKeys.size(); i++) {
            String name = i < keyNames.size() ? keyNames.get(i) : "KEY-" + (i + 1);
            slots.add(new KeySlot(name, apiKeys.get(i)));
        }

        log.info("[KEY-POOL] Iniciado com " + slots.size() + " keys | "
            + "maxReq=" + maxReqPerKey + "/min | maxTok=" + maxTokensPerKey + "/min"
            + " | efetivo=" + (maxTokensPerKey * slots.size()) + " tok/min");
    }

    public synchronized KeySlot acquire(String callerName, int estimatedTokens)
            throws InterruptedException {

        while (true) {
            long now = Instant.now().toEpochMilli();
            pruneAll(now);

            KeySlot best = null;
            long earliestFree = Long.MAX_VALUE;
            String blockReason = "todas-ocupadas";

            int startIdx = roundRobinIdx.get() % slots.size();
            for (int i = 0; i < slots.size(); i++) {
                KeySlot slot = slots.get((startIdx + i) % slots.size());

                if (now < slot.penalizedUntil) {
                    earliestFree = Math.min(earliestFree, slot.penalizedUntil);
                    blockReason = "penalizada(" + slot.name + ")";
                    continue;
                }

                if (now < slot.softBlockedUntil) {
                    earliestFree = Math.min(earliestFree, slot.softBlockedUntil);
                    blockReason = "cooldown-preventivo(" + slot.name + ")";
                    continue;
                }

                if (now - slot.lastCallAt < MIN_INTERVAL_MS) {
                    earliestFree = Math.min(earliestFree, slot.lastCallAt + MIN_INTERVAL_MS);
                    blockReason = "intervalo-minimo";
                    continue;
                }

                if (slot.reqCount() >= maxReqPerKey) {
                    if (!slot.reqTimestamps.isEmpty()) {
                        earliestFree = Math.min(earliestFree, slot.reqTimestamps.peekFirst() + WINDOW_MS + 200);
                    }
                    blockReason = "req/min(" + slot.name + ")";
                    continue;
                }

                if (slot.tokenSum() + estimatedTokens > maxTokensPerKey) {
                    if (!slot.tokenHistory.isEmpty()) {
                        earliestFree = Math.min(earliestFree, slot.tokenHistory.peekFirst()[0] + WINDOW_MS + 200);
                    }
                    blockReason = "tokens/min(" + slot.name + ")";
                    continue;
                }

                double nextReqPct = (slot.reqCount() + 1.0) / maxReqPerKey;
                double nextTokPct = (slot.tokenSum() + estimatedTokens) / (double) maxTokensPerKey;
                if (nextReqPct >= SOFT_REQ_THRESHOLD || nextTokPct >= SOFT_TOKEN_THRESHOLD) {
                    slot.softBlockedUntil = now + SOFT_COOLDOWN_MS;
                    earliestFree = Math.min(earliestFree, slot.softBlockedUntil);
                    blockReason = "proximo-do-limite(" + slot.name + ")";
                    continue;
                }

                if (best == null || score(slot, now) < score(best, now)) {
                    best = slot;
                }
            }

            if (best != null) {
                best.reqTimestamps.addLast(now);
                best.tokenHistory.addLast(new long[]{now, estimatedTokens});
                best.lastCallAt = now;
                best.inFlight = true;
                roundRobinIdx.incrementAndGet();

                int totalTok = slots.stream().mapToInt(KeySlot::tokenSum).sum();
                int totalReq = slots.stream().mapToInt(KeySlot::reqCount).sum();

                log.info("[KEY-POOL] OK " + callerName
                    + " -> " + best.name
                    + " req=" + best.reqCount() + "/" + maxReqPerKey
                    + " tok=" + best.tokenSum() + "/" + maxTokensPerKey
                    + (slots.size() > 1 ? " | pool req=" + totalReq + " tok=" + totalTok : ""));
                return best;
            }

            long waitMs = earliestFree == Long.MAX_VALUE
                ? 2_000L : Math.max(200L, earliestFree - now);

            log.info("[KEY-POOL] WAIT " + callerName
                + " bloqueado [" + blockReason + "] aguardando " + waitMs + "ms");
            Thread.sleep(waitMs);
        }
    }

    public synchronized void release(String keyName, long acquiredAt, int estimatedTokens,
                                     int tokensIn, int tokensOut, boolean success) {
        slots.stream().filter(s -> s.name.equals(keyName)).findFirst().ifPresent(slot -> {
            slot.inFlight = false;
            slot.reconcileReservation(acquiredAt, estimatedTokens, tokensIn + tokensOut);
            if (success) {
                slot.metrics.recordCall(tokensIn, tokensOut);
            }
        });
    }

    public synchronized void report429(String keyName) {
        slots.stream().filter(s -> s.name.equals(keyName)).findFirst().ifPresent(slot -> {
            long until = Instant.now().toEpochMilli() + WINDOW_MS;
            slot.penalizedUntil = until;
            slot.softBlockedUntil = until;
            slot.metrics.record429();
            log.warning("[KEY-POOL] " + slot.name + " penalizada 60s apos 429");
        });
    }

    public synchronized void flushMetricsCycles() {
        slots.forEach(s -> s.metrics.flushCycle());
    }

    public synchronized Map<String, Object> dashboardStatus() {
        long now = Instant.now().toEpochMilli();
        pruneAll(now);

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> keys = new ArrayList<>();

        for (KeySlot slot : slots) {
            Map<String, Object> k = new LinkedHashMap<>();
            String status = now < slot.penalizedUntil
                ? "penalizada"
                : now < slot.softBlockedUntil
                    ? "resfriando"
                    : slot.inFlight ? "em-uso" : "disponivel";
            k.put("name", slot.name);
            k.put("status", status);
            k.put("reqUsed", slot.reqCount());
            k.put("reqLimit", maxReqPerKey);
            k.put("tokUsed", slot.tokenSum());
            k.put("tokLimit", maxTokensPerKey);
            k.put("reqPct", Math.round(slot.reqCount() * 100.0 / maxReqPerKey));
            k.put("tokPct", Math.round(slot.tokenSum() * 100.0 / maxTokensPerKey));
            k.put("metrics", slot.metrics.toMap());
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

        result.put("keys", keys);
        result.put("poolSize", slots.size());
        result.put("totalReqNow", totalReq);
        result.put("totalTokNow", totalTok);
        result.put("totalTokensAllTime", totalTokensAllTime);
        result.put("effectiveTpmLimit", maxTokensPerKey * slots.size());
        result.put("timestamp", Instant.now().toString());
        return result;
    }

    public int poolSize() {
        return slots.size();
    }

    public List<KeySlot> slots() {
        return Collections.unmodifiableList(slots);
    }

    private void pruneAll(long now) {
        slots.forEach(s -> s.prune(now));
    }

    private double score(KeySlot slot, long now) {
        double reqLoad = slot.reqCount() / (double) maxReqPerKey;
        double tokLoad = slot.tokenSum() / (double) maxTokensPerKey;
        double inFlightPenalty = slot.inFlight ? 1.0 : 0.0;
        double recencyPenalty = slot.lastCallAt == 0L ? 0.0 : 1.0 / Math.max(1.0, (now - slot.lastCallAt));
        return (tokLoad * 10.0) + (reqLoad * 5.0) + (inFlightPenalty * 3.0) + recencyPenalty;
    }

    public static class KeySlot {
        public final String name;
        public final String key;
        public final KeyMetrics metrics;
        final Deque<Long> reqTimestamps = new ArrayDeque<>();
        final Deque<long[]> tokenHistory = new ArrayDeque<>();
        long lastCallAt = 0L;
        long penalizedUntil = 0L;
        long softBlockedUntil = 0L;
        boolean inFlight = false;

        KeySlot(String name, String key) {
            this.name = name;
            this.key = key;
            this.metrics = new KeyMetrics(name);
        }

        void prune(long now) {
            reqTimestamps.removeIf(t -> t < now - WINDOW_MS);
            tokenHistory.removeIf(e -> e[0] < now - WINDOW_MS);
        }

        void reconcileReservation(long acquiredAt, int estimatedTokens, int actualTokens) {
            int corrected = Math.max(0, actualTokens);
            for (long[] entry : tokenHistory) {
                if (entry[0] == acquiredAt && entry[1] == estimatedTokens) {
                    entry[1] = corrected;
                    return;
                }
            }
        }

        public long reservationTimestamp() {
            return lastCallAt;
        }

        int reqCount() {
            return reqTimestamps.size();
        }

        int tokenSum() {
            int sum = 0;
            for (long[] e : tokenHistory) {
                sum += (int) e[1];
            }
            return sum;
        }
    }
}
