ALTER TABLE documents ADD COLUMN IF NOT EXISTS content_hash VARCHAR(64);
UPDATE documents
SET content_hash = md5(coalesce(raw_content, '') || ':' || id::text)
WHERE content_hash IS NULL;
ALTER TABLE documents ALTER COLUMN content_hash SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_documents_content_hash ON documents(content_hash);

ALTER TABLE ingestion_jobs ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_ingestion_jobs_document_created ON ingestion_jobs(document_id, created_at DESC);
