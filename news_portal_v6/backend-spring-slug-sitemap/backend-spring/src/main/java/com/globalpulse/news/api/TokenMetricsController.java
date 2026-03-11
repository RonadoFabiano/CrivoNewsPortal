package com.globalpulse.news.api;

import com.globalpulse.news.ai.GroqRateLimiter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * TokenMetricsController — expõe métricas de consumo de tokens por key.
 * Usado pelo dashboard para exibir gráficos em tempo real.
 *
 * Endpoints:
 *   GET /api/admin/token-metrics  — status completo de todas as keys
 */
@RestController
@RequestMapping("/api/admin")
public class TokenMetricsController {

    private final GroqRateLimiter rateLimiter;

    public TokenMetricsController(GroqRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /**
     * GET /api/admin/token-metrics
     * Retorna consumo atual + histórico de ciclos por key.
     */
    @GetMapping("/token-metrics")
    public ResponseEntity<Map<String, Object>> tokenMetrics() {
        return ResponseEntity.ok(rateLimiter.dashboardStatus());
    }
}
