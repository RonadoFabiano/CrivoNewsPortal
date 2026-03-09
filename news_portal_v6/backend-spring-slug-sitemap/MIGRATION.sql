-- ============================================================
-- CRIVO News — Migration Script
-- Execute ANTES de reiniciar o backend com ddl-auto: validate
-- Cole no Supabase → SQL Editor → Run
-- ============================================================

-- ── raw_article: garante todas as colunas ───────────────────
CREATE TABLE IF NOT EXISTS raw_article (
    id              BIGSERIAL PRIMARY KEY,
    ai_retries      INT DEFAULT 0,
    ai_status       VARCHAR(20),
    canonical_url   VARCHAR(512) UNIQUE,
    content_text    TEXT,
    created_at      TIMESTAMPTZ,
    image_url       VARCHAR(500),
    original_category VARCHAR(80),
    published_at    TIMESTAMPTZ,
    raw_description TEXT,
    raw_title       VARCHAR(500),
    slug            VARCHAR(220) UNIQUE,
    source          VARCHAR(120)
);

-- ── processed_article: garante todas as colunas ─────────────
CREATE TABLE IF NOT EXISTS processed_article (
    id              BIGSERIAL PRIMARY KEY,
    raw_article_id  BIGINT UNIQUE,
    slug            VARCHAR(220) UNIQUE,
    link            VARCHAR(512),
    source          VARCHAR(120),
    image_url       VARCHAR(500),
    published_at    TIMESTAMPTZ,
    processed_at    TIMESTAMPTZ,
    ai_title        VARCHAR(300),
    ai_description  TEXT,
    ai_categories   VARCHAR(200),
    ai_tags         VARCHAR(500),
    entities        TEXT,
    entity_status   VARCHAR(20)
);

-- Adiciona colunas novas se ainda não existirem (safe para rodar múltiplas vezes)
ALTER TABLE processed_article ADD COLUMN IF NOT EXISTS ai_tags       VARCHAR(500);
ALTER TABLE processed_article ADD COLUMN IF NOT EXISTS entities      TEXT;
ALTER TABLE processed_article ADD COLUMN IF NOT EXISTS entity_status VARCHAR(20);

-- ── Índices de performance ───────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_raw_ai_status     ON raw_article(ai_status);
CREATE INDEX IF NOT EXISTS idx_raw_published     ON raw_article(published_at DESC);
CREATE INDEX IF NOT EXISTS idx_raw_source        ON raw_article(source);

CREATE INDEX IF NOT EXISTS idx_proc_published    ON processed_article(published_at DESC);
CREATE INDEX IF NOT EXISTS idx_proc_slug         ON processed_article(slug);
CREATE INDEX IF NOT EXISTS idx_proc_raw_id       ON processed_article(raw_article_id);
CREATE INDEX IF NOT EXISTS idx_proc_categories   ON processed_article(ai_categories);
CREATE INDEX IF NOT EXISTS idx_proc_entity_status ON processed_article(entity_status);

-- ============================================================
-- PRONTO — agora pode reiniciar o backend com ddl-auto: validate
-- ============================================================
