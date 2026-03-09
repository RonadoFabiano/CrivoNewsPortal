-- Migration v3: Pipeline HTML bruto → normalização → IA
-- Execute no Supabase SQL Editor ANTES de reiniciar o backend

-- 1. Coluna para HTML bruto temporário
ALTER TABLE raw_article
    ADD COLUMN IF NOT EXISTS html_content TEXT,
    ADD COLUMN IF NOT EXISTS normalize_status VARCHAR(25) DEFAULT 'NORMALIZED';

-- 2. Artigos existentes já têm texto extraído — marcamos como NORMALIZED
UPDATE raw_article SET normalize_status = 'NORMALIZED' WHERE normalize_status IS NULL;

-- 3. Índice para a fila de normalização
CREATE INDEX IF NOT EXISTS idx_raw_normalize_status
    ON raw_article (normalize_status)
    WHERE normalize_status = 'PENDING_NORMALIZE';

-- Verificação
SELECT
    normalize_status,
    COUNT(*) as total,
    SUM(CASE WHEN html_content IS NOT NULL THEN 1 ELSE 0 END) as com_html
FROM raw_article
GROUP BY normalize_status;
