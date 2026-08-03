CREATE TABLE knowledge_documents (
    id CHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    owner_id CHAR(36) NOT NULL,
    transcription_task_id CHAR(36) NOT NULL,
    transcript_version INT NOT NULL,
    title VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_message VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_knowledge_documents_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    CONSTRAINT fk_knowledge_documents_task FOREIGN KEY (transcription_task_id) REFERENCES transcription_tasks(id),
    UNIQUE KEY uk_knowledge_document_source (owner_id, transcription_task_id, transcript_version),
    KEY ix_knowledge_documents_status (status, created_at)
);

CREATE TABLE knowledge_chunks (
    id CHAR(36) PRIMARY KEY,
    knowledge_document_id CHAR(36) NOT NULL,
    chunk_index INT NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    segment_ids JSON NOT NULL,
    text_content TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_knowledge_chunks_document FOREIGN KEY (knowledge_document_id) REFERENCES knowledge_documents(id),
    UNIQUE KEY uk_knowledge_chunk_index (knowledge_document_id, chunk_index)
);

CREATE TABLE knowledge_runs (
    id CHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    owner_id CHAR(36) NOT NULL,
    question TEXT NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    max_tool_calls INT NOT NULL,
    tool_calls_used INT NOT NULL DEFAULT 0,
    result_document JSON,
    failure_message VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_knowledge_runs_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    KEY ix_knowledge_runs_status (status, created_at),
    KEY ix_knowledge_runs_owner (owner_id, created_at)
);

CREATE TABLE knowledge_run_evidence (
    id CHAR(36) PRIMARY KEY,
    knowledge_run_id CHAR(36) NOT NULL,
    knowledge_document_id CHAR(36) NOT NULL,
    knowledge_chunk_id CHAR(36) NOT NULL,
    result_path VARCHAR(512) NOT NULL,
    transcript_segment_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_knowledge_evidence_run FOREIGN KEY (knowledge_run_id) REFERENCES knowledge_runs(id),
    CONSTRAINT fk_knowledge_evidence_document FOREIGN KEY (knowledge_document_id) REFERENCES knowledge_documents(id),
    CONSTRAINT fk_knowledge_evidence_chunk FOREIGN KEY (knowledge_chunk_id) REFERENCES knowledge_chunks(id),
    CONSTRAINT fk_knowledge_evidence_segment FOREIGN KEY (transcript_segment_id) REFERENCES transcript_segments(id),
    UNIQUE KEY uk_knowledge_evidence_path_segment (knowledge_run_id, result_path, transcript_segment_id)
);
