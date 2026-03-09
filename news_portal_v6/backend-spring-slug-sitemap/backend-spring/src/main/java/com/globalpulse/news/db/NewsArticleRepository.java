package com.globalpulse.news.db;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface NewsArticleRepository extends JpaRepository<NewsArticle, Long> {

    Optional<NewsArticle> findByCanonicalUrl(String canonicalUrl);
    Optional<NewsArticle> findBySlug(String slug);

    // ── Fila de IA ────────────────────────────────────────────────

    /** Pega próximo da fila: PENDING, menos de 3 tentativas, mais recente primeiro */
    @Query("SELECT a FROM NewsArticle a WHERE a.aiStatus = 'PENDING' AND a.aiRetries < 3 ORDER BY a.publishedAt DESC")
    List<NewsArticle> findPendingQueue(Pageable pageable);

    boolean existsByAiStatus(String aiStatus);

    // ── API pública (frontend consome) ────────────────────────────

    /** Artigos prontos (DONE ou FAILED) ordenados por data */
    @Query("SELECT a FROM NewsArticle a WHERE a.aiStatus IN ('DONE','FAILED') ORDER BY a.publishedAt DESC")
    List<NewsArticle> findReady(Pageable pageable);

    /** Artigos prontos filtrados por categoria */
    @Query("SELECT a FROM NewsArticle a WHERE a.aiStatus IN ('DONE','FAILED') AND LOWER(a.category) = LOWER(:category) ORDER BY a.publishedAt DESC")
    List<NewsArticle> findReadyByCategory(String category, Pageable pageable);

    /** Stats para monitoramento */
    long countByAiStatus(String aiStatus);
}
