CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE markdown_chunks (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    path            TEXT NOT NULL,
    chunk_index     INTEGER NOT NULL,
    content         TEXT NOT NULL,
    content_tsv     TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('english', content) || to_tsvector('russian', content)
    ) STORED,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT uq_path_chunk UNIQUE (path, chunk_index)
);

CREATE INDEX idx_chunks_content_tsv ON markdown_chunks USING GIN (content_tsv);
CREATE INDEX idx_chunks_path ON markdown_chunks (path);
