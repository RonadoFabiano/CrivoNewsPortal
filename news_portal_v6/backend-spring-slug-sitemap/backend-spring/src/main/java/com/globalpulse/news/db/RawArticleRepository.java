package com.globalpulse.news.db;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RawArticleRepository extends JpaRepository<RawArticle, Long> {

    Optional<RawArticle> findByCanonicalUrl(String canonicalUrl);
    Optional<RawArticle> findBySlug(String slug);

    // ── Fila de normalização HTML ─────────────────────────────────

    /** Artigos com HTML bruto aguardando normalização */
    @Query("SELECT a FROM RawArticle a WHERE a.normalizeStatus = 'PENDING_NORMALIZE' ORDER BY a.scrapedAt ASC")
    List<RawArticle> findPendingNormalize(Pageable pageable);

    long countByNormalizeStatus(String normalizeStatus);

    // ── Fila de IA ────────────────────────────────────────────────

    /** Próximos da fila: PENDING, menos de 3 tentativas, mais recentes primeiro */
    @Query("SELECT a FROM RawArticle a WHERE a.aiStatus = 'PENDING' AND a.aiRetries < 3 ORDER BY a.publishedAt DESC")
    List<RawArticle> findPendingQueue(Pageable pageable);

    boolean existsByAiStatus(String aiStatus);
    long countByAiStatus(String aiStatus);

    /**
     * Retorna artigos das últimas N horas para cache de deduplicação por título.
     * Apenas slug + rawTitle — não carrega conteúdo pesado.
     */
    @Query("SELECT a FROM RawArticle a WHERE a.publishedAt >= :since ORDER BY a.publishedAt DESC")
    List<RawArticle> findRecentTitles(@Param("since") java.time.Instant since);
}
