package com.globalpulse.news.ai.pool;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * KeyMetrics — métricas acumuladas por API key.
 * Thread-safe. Usado pelo dashboard para exibir consumo em tempo real.
 */
public class KeyMetrics {

    public final String keyName;

    // Acumulados totais (desde o boot)
    private final AtomicLong totalRequests   = new AtomicLong(0);
    private final AtomicLong totalTokensIn   = new AtomicLong(0);
    private final AtomicLong totalTokensOut  = new AtomicLong(0);
    private final AtomicLong total429s       = new AtomicLong(0);

    // Histórico de ciclos (últimas 60 entradas = 1h se ciclo=1min)
    private static final int MAX_HISTORY = 60;
    private final Deque<CycleSnapshot> cycleHistory = new ArrayDeque<>();

    // Ciclo atual em aberto
    private volatile CycleSnapshot currentCycle;

    public KeyMetrics(String keyName) {
        this.keyName     = keyName;
        this.currentCycle = new CycleSnapshot(Instant.now());
    }

    /** Registra uma chamada bem-sucedida */
    public synchronized void recordCall(int tokensIn, int tokensOut) {
        totalRequests.incrementAndGet();
        totalTokensIn.addAndGet(tokensIn);
        totalTokensOut.addAndGet(tokensOut);
        currentCycle.requests++;
        currentCycle.tokensIn  += tokensIn;
        currentCycle.tokensOut += tokensOut;
    }

    /** Registra um erro 429 */
    public synchronized void record429() {
        total429s.incrementAndGet();
        currentCycle.throttles++;
    }

    /** Fecha o ciclo atual e abre um novo (chamado pelo scheduler a cada minuto) */
    public synchronized void flushCycle() {
        currentCycle.closedAt = Instant.now();
        cycleHistory.addLast(currentCycle);
        if (cycleHistory.size() > MAX_HISTORY) cycleHistory.pollFirst();
        currentCycle = new CycleSnapshot(Instant.now());
    }

    /** Snapshot para o dashboard */
    public synchronized Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keyName",        keyName);
        m.put("totalRequests",  totalRequests.get());
        m.put("totalTokensIn",  totalTokensIn.get());
        m.put("totalTokensOut", totalTokensOut.get());
        m.put("total429s",      total429s.get());
        m.put("currentCycle",   currentCycle.toMap());

        // Últimos 10 ciclos para o gráfico
        List<Map<String, Object>> history = new ArrayList<>();
        List<CycleSnapshot> recent = new ArrayList<>(cycleHistory);
        int from = Math.max(0, recent.size() - 10);
        for (int i = from; i < recent.size(); i++) {
            history.add(recent.get(i).toMap());
        }
        m.put("cycleHistory", history);
        return m;
    }

    // ── Snapshot de um ciclo (1 minuto) ──────────────────────────────────────

    public static class CycleSnapshot {
        public final Instant openedAt;
        public volatile Instant closedAt;
        public int requests  = 0;
        public int tokensIn  = 0;
        public int tokensOut = 0;
        public int throttles = 0;

        CycleSnapshot(Instant openedAt) { this.openedAt = openedAt; }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("openedAt",  openedAt.toString());
            m.put("closedAt",  closedAt != null ? closedAt.toString() : null);
            m.put("requests",  requests);
            m.put("tokensIn",  tokensIn);
            m.put("tokensOut", tokensOut);
            m.put("throttles", throttles);
            return m;
        }
    }
}
