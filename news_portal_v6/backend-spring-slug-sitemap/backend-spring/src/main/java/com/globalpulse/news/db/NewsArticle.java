package com.globalpulse.news.db;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Entidade principal do banco.
 *
 * Ciclo de vida (status):
 *   PENDING  → salvo pelo NewsIngestionJob, aguardando IA
 *   DONE     → resumo gerado pelo AiSummaryWorker
 *   FAILED   → Ollama falhou após tentativas, exibe description original
 *
 * O frontend consome apenas artigos com summary != null (DONE ou FAILED).
 */
@Entity
@Table(
    name = "news_article",
    indexes = {
        @Index(name = "idx_news_published_at",  columnList = "publishedAt"),
        @Index(name = "idx_news_slug",           columnList = "slug"),
        @Index(name = "idx_news_status",         columnList = "aiStatus"),
        @Index(name = "idx_news_category",       columnList = "category")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_news_canonical_url", columnNames = {"canonicalUrl"})
    }
)
public class NewsArticle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String canonicalUrl;

    @Column(nullable = false, length = 220)
    private String slug;

    /** Título original do scrape */
    @Column(nullable = false, length = 500)
    private String title;

    /** Título reescrito pelo Ollama (mais atraente/clickbait) */
    @Column(length = 500)
    private String aiTitle;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 900)
    private String imageUrl;

    @Column(length = 60)
    private String source;

    @Column(length = 60)
    private String category;

    private Instant publishedAt;

    /** Texto bruto extraído do artigo (alimenta o Ollama) */
    @Column(columnDefinition = "TEXT")
    private String contentText;

    /** Resumo gerado pelo Ollama. Null = ainda na fila (PENDING). */
    @Column(columnDefinition = "TEXT")
    private String summary;

    /**
     * Status da fila de IA:
     *   PENDING → aguardando Ollama
     *   DONE    → resumo gerado com sucesso
     *   FAILED  → Ollama falhou (usa description original)
     */
    @Column(length = 20, nullable = false)
    private String aiStatus = "PENDING";

    /** Quantas vezes o Ollama tentou e falhou (limite: 3) */
    @Column(nullable = false)
    private int aiRetries = 0;

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (aiStatus == null) aiStatus = "PENDING";
    }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }

    // ── getters/setters ──────────────────────────────────────────

    public Long getId() { return id; }

    public String getCanonicalUrl() { return canonicalUrl; }
    public void setCanonicalUrl(String v) { this.canonicalUrl = v; }

    public String getSlug() { return slug; }
    public void setSlug(String v) { this.slug = v; }

    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }

    public String getAiTitle() { return aiTitle; }
    public void setAiTitle(String v) { this.aiTitle = v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String v) { this.imageUrl = v; }

    public String getSource() { return source; }
    public void setSource(String v) { this.source = v; }

    public String getCategory() { return category; }
    public void setCategory(String v) { this.category = v; }

    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant v) { this.publishedAt = v; }

    public String getContentText() { return contentText; }
    public void setContentText(String v) { this.contentText = v; }

    public String getSummary() { return summary; }
    public void setSummary(String v) { this.summary = v; }

    public String getAiStatus() { return aiStatus; }
    public void setAiStatus(String v) { this.aiStatus = v; }

    public int getAiRetries() { return aiRetries; }
    public void setAiRetries(int v) { this.aiRetries = v; }

    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
