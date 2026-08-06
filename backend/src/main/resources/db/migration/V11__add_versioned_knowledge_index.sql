SET @knowledge_document_active_index_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'knowledge_documents'
      AND column_name = 'active_index_version_id'
);
SET @knowledge_document_active_index_column_sql := IF(
    @knowledge_document_active_index_column_exists = 0,
    'ALTER TABLE knowledge_documents ADD COLUMN active_index_version_id CHAR(36) NULL AFTER organized_document_version',
    'SELECT 1'
);
PREPARE knowledge_document_active_index_column_statement FROM @knowledge_document_active_index_column_sql;
EXECUTE knowledge_document_active_index_column_statement;
DEALLOCATE PREPARE knowledge_document_active_index_column_statement;

SET @knowledge_chunk_index_version_column_exists := (
    SELECT COUNT(*)
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'knowledge_chunks'
      AND column_name = 'knowledge_index_version_id'
);
SET @knowledge_chunk_index_version_column_sql := IF(
    @knowledge_chunk_index_version_column_exists = 0,
    'ALTER TABLE knowledge_chunks ADD COLUMN knowledge_index_version_id CHAR(36) NULL AFTER knowledge_document_id',
    'SELECT 1'
);
PREPARE knowledge_chunk_index_version_column_statement FROM @knowledge_chunk_index_version_column_sql;
EXECUTE knowledge_chunk_index_version_column_statement;
DEALLOCATE PREPARE knowledge_chunk_index_version_column_statement;

ALTER TABLE knowledge_chunks
    ADD KEY ix_knowledge_chunk_document (knowledge_document_id);

ALTER TABLE knowledge_chunks
    DROP INDEX uk_knowledge_chunk_index;

ALTER TABLE knowledge_chunks
    ADD UNIQUE KEY uk_knowledge_chunk_version_index (knowledge_index_version_id, chunk_index),
    ADD KEY ix_knowledge_chunk_version (knowledge_index_version_id, chunk_index);

CREATE TABLE knowledge_index_versions (
    id CHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    knowledge_document_id CHAR(36) NOT NULL,
    generation INT NOT NULL,
    organized_document_id CHAR(36) NOT NULL,
    organized_document_version BIGINT NOT NULL,
    configuration_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_stage VARCHAR(32) NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    topic_count INT NOT NULL DEFAULT 0,
    chunk_count INT NOT NULL DEFAULT 0,
    indexed_chunk_count INT NOT NULL DEFAULT 0,
    failure_message VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_knowledge_index_version_document FOREIGN KEY (knowledge_document_id) REFERENCES knowledge_documents(id),
    CONSTRAINT fk_knowledge_index_version_organized_document FOREIGN KEY (organized_document_id) REFERENCES organized_documents(id),
    UNIQUE KEY uk_knowledge_index_generation (knowledge_document_id, generation),
    KEY ix_knowledge_index_status (status, updated_at)
);

ALTER TABLE knowledge_documents
    ADD CONSTRAINT fk_knowledge_document_active_index_version FOREIGN KEY (active_index_version_id) REFERENCES knowledge_index_versions(id);

ALTER TABLE knowledge_chunks
    ADD CONSTRAINT fk_knowledge_chunk_index_version FOREIGN KEY (knowledge_index_version_id) REFERENCES knowledge_index_versions(id);

CREATE TABLE knowledge_index_stage_attempts (
    id CHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    knowledge_index_version_id CHAR(36) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    attempt_number INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    progress_percent INT NOT NULL DEFAULT 0,
    completed_count INT NOT NULL DEFAULT 0,
    total_count INT NOT NULL DEFAULT 0,
    queued_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    next_retry_at DATETIME(6) NULL,
    error_code VARCHAR(128) NULL,
    error_message VARCHAR(1000) NULL,
    result_snapshot JSON NULL,
    CONSTRAINT fk_knowledge_index_stage_version FOREIGN KEY (knowledge_index_version_id) REFERENCES knowledge_index_versions(id),
    UNIQUE KEY uk_knowledge_index_stage_attempt (knowledge_index_version_id, stage, attempt_number),
    KEY ix_knowledge_index_stage_status (status, next_retry_at)
);

CREATE TABLE knowledge_topics (
    id CHAR(36) PRIMARY KEY,
    knowledge_index_version_id CHAR(36) NOT NULL,
    source_topic_block_id CHAR(36) NULL,
    topic_index INT NOT NULL,
    title VARCHAR(512) NOT NULL,
    text_content MEDIUMTEXT NOT NULL,
    speaker_ids JSON NULL,
    source_segment_ids JSON NOT NULL,
    source_fragments JSON NULL,
    source_unit_snapshots JSON NOT NULL,
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_knowledge_topic_index_version FOREIGN KEY (knowledge_index_version_id) REFERENCES knowledge_index_versions(id),
    UNIQUE KEY uk_knowledge_topic_index (knowledge_index_version_id, topic_index),
    KEY ix_knowledge_topic_version_time (knowledge_index_version_id, start_ms)
);

CREATE TABLE knowledge_chunk_topics (
    id CHAR(36) PRIMARY KEY,
    knowledge_chunk_id CHAR(36) NOT NULL,
    knowledge_topic_id CHAR(36) NOT NULL,
    topic_order_in_chunk INT NOT NULL,
    chunk_index_in_topic INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_knowledge_chunk_topic_chunk FOREIGN KEY (knowledge_chunk_id) REFERENCES knowledge_chunks(id),
    CONSTRAINT fk_knowledge_chunk_topic_topic FOREIGN KEY (knowledge_topic_id) REFERENCES knowledge_topics(id),
    UNIQUE KEY uk_knowledge_chunk_topic (knowledge_chunk_id, knowledge_topic_id),
    KEY ix_knowledge_chunk_topic_order (knowledge_topic_id, chunk_index_in_topic)
);
