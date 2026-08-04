ALTER TABLE transcription_tasks
    ADD COLUMN current_phase VARCHAR(48) NULL AFTER current_stage,
    ADD COLUMN cancel_requested_at DATETIME(6) NULL AFTER failed_stage;

UPDATE transcription_tasks
SET status = CASE status
    WHEN 'SUBMITTING' THEN 'RUNNING'
    WHEN 'PROVIDER_RUNNING' THEN 'RUNNING'
    WHEN 'INDEXING' THEN 'RUNNING'
    WHEN 'RETRYABLE_FAILED' THEN 'RETRY_WAIT'
    WHEN 'FINAL_FAILED' THEN 'FAILED'
    WHEN 'SUBMISSION_UNKNOWN' THEN 'FAILED'
    ELSE status
END;

UPDATE transcription_tasks
SET current_phase = CASE
    WHEN current_stage IN ('ASR_SUBMIT', 'ASR_POLL', 'TRANSCRIPT_PERSIST') THEN 'TRANSCRIPTION'
    WHEN current_stage IN ('KNOWLEDGE_PREPARE', 'KNOWLEDGE_INDEX') THEN 'KNOWLEDGE_BUILD'
    WHEN status = 'SUCCEEDED' THEN 'COMPLETED'
    ELSE current_phase
END
WHERE current_phase IS NULL;

ALTER TABLE outbox_events
    ADD COLUMN deduplication_key VARCHAR(255) NULL AFTER event_type,
    ADD UNIQUE KEY uk_outbox_deduplication_key (deduplication_key);

CREATE TABLE organized_documents (
    id CHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    owner_id CHAR(36) NOT NULL,
    transcription_task_id CHAR(36) NOT NULL,
    transcript_version INT NOT NULL,
    title VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL,
    structure_document JSON,
    plain_text MEDIUMTEXT,
    failure_message VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_organized_documents_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    CONSTRAINT fk_organized_documents_task FOREIGN KEY (transcription_task_id) REFERENCES transcription_tasks(id),
    UNIQUE KEY uk_organized_document_source (owner_id, transcription_task_id, transcript_version),
    KEY ix_organized_document_status (status, created_at)
);

CREATE TABLE organized_document_blocks (
    id CHAR(36) PRIMARY KEY,
    organized_document_id CHAR(36) NOT NULL,
    block_index INT NOT NULL,
    block_type VARCHAR(32) NOT NULL,
    speaker_label VARCHAR(128),
    topic_title VARCHAR(512),
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    source_segment_ids JSON NOT NULL,
    text_content TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_organized_block_document FOREIGN KEY (organized_document_id) REFERENCES organized_documents(id),
    UNIQUE KEY uk_organized_block_index (organized_document_id, block_index),
    KEY ix_organized_block_document_time (organized_document_id, start_ms)
);

ALTER TABLE knowledge_documents
    ADD COLUMN organized_document_id CHAR(36) NULL AFTER transcription_task_id,
    ADD COLUMN organized_document_version INT NULL AFTER transcript_version,
    ADD CONSTRAINT fk_knowledge_document_organized_document FOREIGN KEY (organized_document_id) REFERENCES organized_documents(id);

ALTER TABLE knowledge_chunks
    ADD COLUMN organized_document_block_ids JSON NULL AFTER segment_ids;

ALTER TABLE analysis_runs
    ADD COLUMN organized_document_id CHAR(36) NULL AFTER transcription_task_id,
    ADD CONSTRAINT fk_analysis_organized_document FOREIGN KEY (organized_document_id) REFERENCES organized_documents(id);
