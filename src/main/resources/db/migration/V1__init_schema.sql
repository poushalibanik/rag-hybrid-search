CREATE TABLE documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), file_name TEXT NOT NULL, source_path TEXT,
    file_type TEXT NOT NULL, raw_content TEXT, status TEXT NOT NULL DEFAULT 'PENDING',
    chunking_strategy TEXT NOT NULL DEFAULT 'RECURSIVE', created_at TIMESTAMPTZ DEFAULT now(), updated_at TIMESTAMPTZ DEFAULT now()
);
CREATE TABLE chunks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), document_id UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    content TEXT NOT NULL, chunk_index INTEGER NOT NULL, section_heading TEXT, chunking_strategy TEXT NOT NULL,
    char_count INTEGER NOT NULL, token_estimate INTEGER, is_duplicate BOOLEAN DEFAULT false, qdrant_point_id TEXT, created_at TIMESTAMPTZ DEFAULT now()
);
CREATE TABLE ingestion_jobs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), document_id UUID NOT NULL REFERENCES documents(id), status TEXT NOT NULL DEFAULT 'QUEUED',
    error_message TEXT, started_at TIMESTAMPTZ, completed_at TIMESTAMPTZ, created_at TIMESTAMPTZ DEFAULT now()
);
CREATE TABLE eval_cases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), question TEXT NOT NULL, expected_answer TEXT NOT NULL,
    expected_chunk_ids TEXT[], difficulty TEXT NOT NULL DEFAULT 'MEDIUM', category TEXT, created_at TIMESTAMPTZ DEFAULT now()
);
CREATE TABLE eval_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), eval_case_id UUID NOT NULL REFERENCES eval_cases(id), actual_answer TEXT,
    retrieved_chunk_ids TEXT[], answer_correctness_score FLOAT, faithfulness_score FLOAT, retrieval_relevance_score FLOAT,
    citation_accuracy_score FLOAT, chunking_strategy TEXT, retrieval_mode TEXT, run_timestamp TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_chunks_document_id ON chunks(document_id);
CREATE INDEX idx_chunks_qdrant_point_id ON chunks(qdrant_point_id);
