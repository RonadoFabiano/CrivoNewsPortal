package com.globalpulse.news.db;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Resultado do processamento pelo Ollama.
 * Relacionado 1-para-1 com RawArticle.
 *
 * Contém:
 *  - aiTitle:       título reescrito (atraente/clickbait)
 *  - aiDescription: descrição envolvente (2-3 frases)
 *  - aiCategories:  categorias classificadas pelo Ollama (CSV: "Política,Brasil,Geral")
 *
 * Campos copiados do RawArticle para facilitar queries do frontend
 * sem precisar de JOIN: slug, link, imageUrl, source, publishedAt.
 */
@Entity
@Table(
    name = "processed_article",
    indexes = {
        @Index(name = "idx_proc_published_at", columnList = "publishedAt"),
        @Index(name = "idx_proc_slug",          columnList = "slug"),
        @Index(name = "idx_proc_raw_id",        columnList = "rawArticleId"),
        @Index(name = "idx_proc_categories",    columnList = "aiCategories")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_proc_raw_id", columnNames = {"rawArticleId"}),
        @UniqueConstraint(name = "uk_proc_slug",   columnNames = {"slug"})
    }
)
public class ProcessedArticle {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** FK para o RawArticle de origem */
    @Column(nullable = false)
    private Long rawArticleId;

    // ── Campos copiados do scrape (para queries simples sem JOIN) ─
    @Column(nullable = false, length = 220)
    private String slug;

    @Column(nullable = false, length = 512)
    private String link;  // canonical URL

    /** Imagem original do scrape — copiada, nunca alterada */
    @Column(length = 900)
    private String imageUrl;

    @Column(length = 80)
    private String source;

    private Instant publishedAt;

    // ── Gerado pelo Ollama ─────────────────────────────────────────

    /** Título reescrito pelo Ollama (atraente, sem nome de jornalista) */
    @Column(length = 600)
    private String aiTitle;

    /** Descrição envolvente gerada pelo Ollama (2-3 frases) */
    @Column(columnDefinition = "TEXT")
    private String aiDescription;

    /**
     * Categorias classificadas pelo Ollama — CSV ordenado por relevância.
     * Ex: "Política,Brasil,Geral"
     * Máx 5 categorias por notícia.
     */
    @Column(length = 200)
    private String aiCategories;

    /**
     * Tags SEO geradas pelo Ollama para páginas /noticias/{tag}.
     * CSV, ex: "inteligencia-artificial,economia-digital,startups"
     * Derivadas de aiCategories + topics das entidades (slugificadas).
     */
    @Column(length = 500)
    private String aiTags;

    /**
     * Entidades extraídas pelo OllamaEntityExtractor — JSON.
     * Formato: {"countries":["China","Irã"],"people":["Wang Yi"],"topics":["Geopolítica"]}
     * NULL até o worker de entidades processar o artigo.
     */
    @Column(columnDefinition = "TEXT")
    private String entities;

    /** Status da extração de entidades: null | DONE | FAILED */
    @Column(length = 20)
    private String entityStatus;

    // ── Campos analíticos (Camada 2 + 5 do modelo de dados) ───────

    /** Escopo geográfico: nacional | internacional | ambos */
    @Column(length = 20)
    private String scope;

    /** Tom da notícia: urgente | negativo | positivo | neutro | investigativo */
    @Column(length = 20)
    private String tone;

    /** Fato central em frase curta (máx 120 chars) */
    @Column(length = 120)
    private String keyFact;

    /** Há vítimas (mortos/feridos) mencionados */
    private Boolean hasVictims;

    /** Número de vítimas (-1 = não mencionado) */
    private Integer victimCount;

    /** Estado brasileiro principal mencionado */
    @Column(length = 60)
    private String locationState;

    /** Cidade brasileira principal mencionada */
    @Column(length = 100)
    private String locationCity;

    private Instant processedAt;

    @PrePersist
    void prePersist() {
        if (processedAt == null) processedAt = Instant.now();
    }

    // ── helpers ───────────────────────────────────────────────────

    /** Retorna as categorias como array. Ex: ["Política","Brasil","Geral"] */
    public String[] categoriesArray() {
        if (aiCategories == null || aiCategories.isBlank()) return new String[]{"Geral"};
        return aiCategories.split(",");
    }

    /** Verifica se o artigo pertence a uma categoria (case-insensitive) */
    public boolean hasCategory(String cat) {
        if (cat == null || aiCategories == null) return false;
        for (String c : categoriesArray()) {
            if (c.trim().equalsIgnoreCase(cat.trim())) return true;
        }
        return false;
    }

    // ── getters/setters ───────────────────────────────────────────

    public Long    getId()              { return id; }

    public Long    getRawArticleId()    { return rawArticleId; }
    public void    setRawArticleId(Long v) { this.rawArticleId = v; }

    public String  getSlug()            { return slug; }
    public void    setSlug(String v)    { this.slug = v; }

    public String  getLink()            { return link; }
    public void    setLink(String v)    { this.link = v; }

    public String  getImageUrl()        { return imageUrl; }
    public void    setImageUrl(String v){ this.imageUrl = v; }

    public String  getSource()          { return source; }
    public void    setSource(String v)  { this.source = v; }

    public Instant getPublishedAt()     { return publishedAt; }
    public void    setPublishedAt(Instant v) { this.publishedAt = v; }

    public String  getAiTitle()         { return aiTitle; }
    public void    setAiTitle(String v) { this.aiTitle = v; }

    public String  getAiDescription()   { return aiDescription; }
    public void    setAiDescription(String v) { this.aiDescription = v; }

    public String  getAiCategories()    { return aiCategories; }
    public void    setAiCategories(String v) { this.aiCategories = v; }

    public Instant getProcessedAt()     { return processedAt; }

    public String  getAiTags()           { return aiTags; }
    public void    setAiTags(String v)   { this.aiTags = v; }

    public String  getEntities()         { return entities; }
    public void    setEntities(String v) { this.entities = v; }

    public String  getEntityStatus()     { return entityStatus; }
    public void    setEntityStatus(String v) { this.entityStatus = v; }

    public String  getScope()            { return scope; }
    public void    setScope(String v)    { this.scope = v; }

    public String  getTone()             { return tone; }
    public void    setTone(String v)     { this.tone = v; }

    public String  getKeyFact()          { return keyFact; }
    public void    setKeyFact(String v)  { this.keyFact = v; }

    public Boolean getHasVictims()       { return hasVictims; }
    public void    setHasVictims(Boolean v) { this.hasVictims = v; }

    public Integer getVictimCount()      { return victimCount; }
    public void    setVictimCount(Integer v) { this.victimCount = v; }

    public String  getLocationState()    { return locationState; }
    public void    setLocationState(String v) { this.locationState = v; }

    public String  getLocationCity()     { return locationCity; }
    public void    setLocationCity(String v)  { this.locationCity = v; }
}
