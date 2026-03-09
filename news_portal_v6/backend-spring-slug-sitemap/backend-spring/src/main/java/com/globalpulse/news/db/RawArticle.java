package com.globalpulse.news.db;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Dados brutos do scrape — IMUTÁVEL após inserção.
 * Nunca alterado pelo Ollama ou por qualquer processamento posterior.
 *
 * Ciclo: PENDING → DONE | FAILED
 */
@Entity
@Table(
    name = "raw_article",
    indexes = {
        @Index(name = "idx_raw_published_at", columnList = "publishedAt"),
        @Index(name = "idx_raw_status",       columnList = "aiStatus"),
        @Index(name = "idx_raw_source",        columnList = "source")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_raw_canonical_url", columnNames = {"canonicalUrl"}),
        @UniqueConstraint(name = "uk_raw_slug",          columnNames = {"slug"})
    }
)
public class RawArticle {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── URL canônica (chave de deduplicação) ──────────────────────
    @Column(nullable = false, length = 512)
    private String canonicalUrl;

    @Column(nullable = false, length = 220)
    private String slug;

    // ── Dados originais do scrape (nunca alterados) ───────────────
    @Column(nullable = false, length = 600)
    private String rawTitle;

    /** og:description do RSS — pode ser curta */
    @Column(columnDefinition = "TEXT")
    private String rawDescription;

    /** HTML bruto temporário — preenchido pelo scraper, apagado após normalização */
    @Column(columnDefinition = "TEXT")
    private String htmlContent;

    /** Status da normalização do HTML */
    @Column(length = 20)
    private String normalizeStatus; // NULL | PENDING_NORMALIZE | NORMALIZED

    /** Texto completo extraído do artigo pelo ArticleExtractor */
    @Column(columnDefinition = "TEXT")
    private String rawContentText;

    /** Imagem original do scrape — NUNCA sobrescrever */
    @Column(length = 900)
    private String imageUrl;

    @Column(length = 80)
    private String source;

    /** Categoria original do feed/scraper (ex: "Economia") */
    @Column(length = 60)
    private String originalCategory;

    private Instant publishedAt;
    private Instant scrapedAt;  // quando foi capturado

    // ── Status da fila de IA ──────────────────────────────────────
    /** PENDING | DONE | FAILED */
    @Column(length = 20, nullable = false)
    private String aiStatus = "PENDING";

    @Column(nullable = false)
    private int aiRetries = 0;

    @PrePersist
    void prePersist() {
        if (scrapedAt == null) scrapedAt = Instant.now();
        if (aiStatus  == null) aiStatus  = "PENDING";
    }

    // ── getters/setters ───────────────────────────────────────────

    public Long    getId()               { return id; }

    public String  getCanonicalUrl()     { return canonicalUrl; }
    public void    setCanonicalUrl(String v) { this.canonicalUrl = v; }

    public String  getSlug()             { return slug; }
    public void    setSlug(String v)     { this.slug = v; }

    public String  getRawTitle()         { return rawTitle; }
    public void    setRawTitle(String v) { this.rawTitle = v; }

    public String  getRawDescription()   { return rawDescription; }
    public void    setRawDescription(String v) { this.rawDescription = v; }

    public String  getHtmlContent()      { return htmlContent; }
    public void    setHtmlContent(String v)  { this.htmlContent = v; }

    public String  getNormalizeStatus()  { return normalizeStatus; }
    public void    setNormalizeStatus(String v) { this.normalizeStatus = v; }

    public String  getRawContentText()   { return rawContentText; }
    public void    setRawContentText(String v) { this.rawContentText = v; }

    public String  getImageUrl()         { return imageUrl; }
    public void    setImageUrl(String v) { this.imageUrl = v; }

    public String  getSource()           { return source; }
    public void    setSource(String v)   { this.source = v; }

    public String  getOriginalCategory() { return originalCategory; }
    public void    setOriginalCategory(String v) { this.originalCategory = v; }

    public Instant getPublishedAt()      { return publishedAt; }
    public void    setPublishedAt(Instant v) { this.publishedAt = v; }

    public Instant getScrapedAt()        { return scrapedAt; }

    public String  getAiStatus()         { return aiStatus; }
    public void    setAiStatus(String v) { this.aiStatus = v; }

    public int     getAiRetries()        { return aiRetries; }
    public void    setAiRetries(int v)   { this.aiRetries = v; }
}
