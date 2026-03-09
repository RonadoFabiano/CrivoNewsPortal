-- Migration: adiciona campos analíticos à tabela processed_article
-- Execute uma vez no banco de produção (Supabase/PostgreSQL)
-- Todos os campos têm DEFAULT para não quebrar registros existentes

ALTER TABLE processed_article
    ADD COLUMN IF NOT EXISTS scope          VARCHAR(20)  DEFAULT 'nacional',
    ADD COLUMN IF NOT EXISTS tone           VARCHAR(20)  DEFAULT 'neutro',
    ADD COLUMN IF NOT EXISTS key_fact       VARCHAR(120) DEFAULT '',
    ADD COLUMN IF NOT EXISTS has_victims    BOOLEAN      DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS victim_count   INTEGER      DEFAULT -1,
    ADD COLUMN IF NOT EXISTS location_state VARCHAR(60)  DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS location_city  VARCHAR(100) DEFAULT NULL;

-- Índices úteis para queries do mapa e filtros
CREATE INDEX IF NOT EXISTS idx_proc_scope    ON processed_article (scope);
CREATE INDEX IF NOT EXISTS idx_proc_tone     ON processed_article (tone);
CREATE INDEX IF NOT EXISTS idx_proc_victims  ON processed_article (has_victims) WHERE has_victims = TRUE;
CREATE INDEX IF NOT EXISTS idx_proc_state    ON processed_article (location_state) WHERE location_state IS NOT NULL;
