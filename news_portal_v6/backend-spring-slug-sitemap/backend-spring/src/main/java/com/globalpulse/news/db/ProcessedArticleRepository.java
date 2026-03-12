package com.globalpulse.news.db;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ProcessedArticleRepository extends JpaRepository<ProcessedArticle, Long> {

    Optional<ProcessedArticle> findBySlug(String slug);
    Optional<ProcessedArticle> findByLink(String link);
    Optional<ProcessedArticle> findByRawArticleId(Long rawArticleId);

    // Feed principal

    @Query("SELECT a FROM ProcessedArticle a WHERE a.aiTitle IS NOT NULL AND a.aiTitle <> '' ORDER BY a.publishedAt DESC")
    List<ProcessedArticle> findAllReady(Pageable pageable);

    @Query("""
        SELECT a.source, a.aiCategories
        FROM ProcessedArticle a
        WHERE a.aiTitle IS NOT NULL AND a.aiTitle <> ''
        """)
    List<Object[]> findSourceCategoryRows();

    @Query("""
        SELECT a FROM ProcessedArticle a
        WHERE a.aiTitle IS NOT NULL AND a.aiTitle <> ''
          AND LOWER(a.aiCategories) LIKE LOWER(CONCAT('%', :category, '%'))
        ORDER BY a.publishedAt DESC
        """)
    List<ProcessedArticle> findByCategory(@Param("category") String category, Pageable pageable);

    // Paginas SEO por tag (/noticias/{tag})

    @Query("""
        SELECT a FROM ProcessedArticle a
        WHERE LOWER(a.aiTags) LIKE LOWER(CONCAT('%', :tag, '%'))
        ORDER BY a.publishedAt DESC
        """)
    List<ProcessedArticle> findByTag(@Param("tag") String tag, Pageable pageable);

    // Paginas de entidade (/pais /pessoa /topico)

    @Query("""
        SELECT a FROM ProcessedArticle a
        WHERE a.entities IS NOT NULL
          AND LOWER(a.entities) LIKE LOWER(CONCAT('%\"', :country, '\"%'))
        ORDER BY a.publishedAt DESC
        """)
    List<ProcessedArticle> findByCountry(@Param("country") String country, Pageable pageable);

    @Query("""
        SELECT a FROM ProcessedArticle a
        WHERE a.entities IS NOT NULL
          AND (
            LOWER(a.entities) LIKE LOWER(CONCAT('%\"', :person, '\"%'))
            OR LOWER(REPLACE(a.entities, ' ', '-')) LIKE LOWER(CONCAT('%', :personSlug, '%'))
          )
        ORDER BY a.publishedAt DESC
        """)
    List<ProcessedArticle> findByPerson(@Param("person") String person, @Param("personSlug") String personSlug, Pageable pageable);

    @Query("""
        SELECT a FROM ProcessedArticle a
        WHERE LOWER(a.aiTags) LIKE LOWER(CONCAT('%', :topicSlug, '%'))
           OR (a.entities IS NOT NULL
               AND LOWER(a.entities) LIKE LOWER(CONCAT('%\"', :topic, '\"%')))
        ORDER BY a.publishedAt DESC
        """)
    List<ProcessedArticle> findByTopic(
        @Param("topic") String topic,
        @Param("topicSlug") String topicSlug,
        Pageable pageable);

    // Trending topics (ultimas 12h)

    @Query("""
        SELECT a FROM ProcessedArticle a
        WHERE a.publishedAt >= :since
        ORDER BY a.publishedAt DESC
        """)
    List<ProcessedArticle> findRecentAll(@Param("since") Instant since);

    @Query("""
        SELECT a FROM ProcessedArticle a
        WHERE a.publishedAt >= :since
          AND a.entityStatus = 'DONE'
        ORDER BY a.publishedAt DESC
        """)
    List<ProcessedArticle> findRecentWithEntities(@Param("since") Instant since);

    // Fila de entidades

    @Query("""
        SELECT a FROM ProcessedArticle a
        WHERE a.entityStatus IS NULL
        ORDER BY a.publishedAt DESC
        """)
    List<ProcessedArticle> findPendingEntities(Pageable pageable);

    @Query("""
        SELECT a FROM ProcessedArticle a
        WHERE
          LOWER(a.aiTitle)          LIKE LOWER(CONCAT('%', :q, '%'))
          OR LOWER(a.aiDescription) LIKE LOWER(CONCAT('%', :q, '%'))
          OR LOWER(a.aiTags)        LIKE LOWER(CONCAT('%', :q, '%'))
          OR LOWER(a.aiCategories)  LIKE LOWER(CONCAT('%', :q, '%'))
          OR (a.entities IS NOT NULL AND LOWER(a.entities) LIKE LOWER(CONCAT('%', :q, '%')))
          OR LOWER(a.source)        LIKE LOWER(CONCAT('%', :q, '%'))
        ORDER BY a.publishedAt DESC
        """)
    List<ProcessedArticle> search(@Param("q") String q, Pageable pageable);

    long count();
    long countByEntityStatus(String status);
}
