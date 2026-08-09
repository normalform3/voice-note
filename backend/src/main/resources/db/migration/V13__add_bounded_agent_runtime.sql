ALTER TABLE transcription_tasks
    ADD COLUMN occurred_at DATETIME(6) NULL AFTER transcript_version,
    ADD COLUMN scene_type VARCHAR(32) NOT NULL DEFAULT 'OTHER' AFTER occurred_at,
    ADD COLUMN subject VARCHAR(512) NULL AFTER scene_type,
    ADD COLUMN tags JSON NULL AFTER subject;

UPDATE transcription_tasks
SET occurred_at = created_at,
    tags = JSON_ARRAY()
WHERE occurred_at IS NULL OR tags IS NULL;

ALTER TABLE transcription_tasks
    MODIFY occurred_at DATETIME(6) NOT NULL,
    ADD KEY ix_transcription_task_metadata (owner_id, scene_type, occurred_at);

ALTER TABLE knowledge_runs
    ADD COLUMN scope_type VARCHAR(32) NOT NULL DEFAULT 'ALL_DOCUMENTS' AFTER question,
    ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' AFTER scope_type,
    ADD COLUMN skill_id VARCHAR(128) NOT NULL DEFAULT 'knowledge-qa' AFTER time_zone,
    ADD COLUMN skill_version VARCHAR(64) NOT NULL DEFAULT 'legacy-v1' AFTER skill_id,
    ADD COLUMN skill_snapshot JSON NULL AFTER skill_version,
    ADD COLUMN skill_hash CHAR(64) NULL AFTER skill_snapshot,
    ADD COLUMN max_model_calls INT NOT NULL DEFAULT 7 AFTER model_id,
    ADD COLUMN model_calls_used INT NOT NULL DEFAULT 0 AFTER max_model_calls,
    ADD COLUMN max_agent_turns INT NOT NULL DEFAULT 6 AFTER model_calls_used,
    ADD COLUMN agent_turns_used INT NOT NULL DEFAULT 0 AFTER max_agent_turns,
    ADD COLUMN lease_until DATETIME(6) NULL AFTER tool_calls_used,
    ADD COLUMN started_at DATETIME(6) NULL AFTER lease_until,
    ADD COLUMN completed_at DATETIME(6) NULL AFTER started_at;

CREATE TABLE knowledge_run_documents (
    id CHAR(36) PRIMARY KEY,
    knowledge_run_id CHAR(36) NOT NULL,
    transcription_task_id CHAR(36) NOT NULL,
    knowledge_document_id CHAR(36) NULL,
    knowledge_index_version_id CHAR(36) NULL,
    metadata_snapshot JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_knowledge_run_document_run FOREIGN KEY (knowledge_run_id) REFERENCES knowledge_runs(id),
    CONSTRAINT fk_knowledge_run_document_task FOREIGN KEY (transcription_task_id) REFERENCES transcription_tasks(id),
    CONSTRAINT fk_knowledge_run_document_document FOREIGN KEY (knowledge_document_id) REFERENCES knowledge_documents(id),
    CONSTRAINT fk_knowledge_run_document_index FOREIGN KEY (knowledge_index_version_id) REFERENCES knowledge_index_versions(id),
    UNIQUE KEY uk_knowledge_run_document (knowledge_run_id, transcription_task_id),
    KEY ix_knowledge_run_document_scope (knowledge_run_id, knowledge_document_id)
);

CREATE TABLE knowledge_run_steps (
    id CHAR(36) PRIMARY KEY,
    knowledge_run_id CHAR(36) NOT NULL,
    step_index INT NOT NULL,
    step_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    tool_call_id VARCHAR(255) NULL,
    tool_name VARCHAR(255) NULL,
    input_document JSON NULL,
    output_document JSON NULL,
    summary_text VARCHAR(1000) NULL,
    error_code VARCHAR(128) NULL,
    error_message VARCHAR(1000) NULL,
    duration_ms BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    CONSTRAINT fk_knowledge_run_step_run FOREIGN KEY (knowledge_run_id) REFERENCES knowledge_runs(id),
    UNIQUE KEY uk_knowledge_run_step_index (knowledge_run_id, step_index),
    KEY ix_knowledge_run_step_call (knowledge_run_id, tool_call_id)
);

CREATE TABLE knowledge_run_sources (
    id CHAR(36) PRIMARY KEY,
    knowledge_run_id CHAR(36) NOT NULL,
    source_ref VARCHAR(128) NOT NULL,
    source_kind VARCHAR(32) NOT NULL,
    knowledge_document_id CHAR(36) NULL,
    transcription_task_id CHAR(36) NULL,
    knowledge_chunk_id CHAR(36) NULL,
    transcript_segment_id CHAR(36) NULL,
    topic VARCHAR(512) NULL,
    speaker_id VARCHAR(128) NULL,
    start_ms BIGINT NULL,
    end_ms BIGINT NULL,
    text_content TEXT NULL,
    external_label VARCHAR(512) NULL,
    external_url VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_knowledge_run_source_run FOREIGN KEY (knowledge_run_id) REFERENCES knowledge_runs(id),
    CONSTRAINT fk_knowledge_run_source_document FOREIGN KEY (knowledge_document_id) REFERENCES knowledge_documents(id),
    CONSTRAINT fk_knowledge_run_source_task FOREIGN KEY (transcription_task_id) REFERENCES transcription_tasks(id),
    CONSTRAINT fk_knowledge_run_source_chunk FOREIGN KEY (knowledge_chunk_id) REFERENCES knowledge_chunks(id),
    CONSTRAINT fk_knowledge_run_source_segment FOREIGN KEY (transcript_segment_id) REFERENCES transcript_segments(id),
    UNIQUE KEY uk_knowledge_run_source_ref (knowledge_run_id, source_ref)
);

ALTER TABLE knowledge_run_evidence
    DROP INDEX uk_knowledge_evidence_path_segment,
    MODIFY knowledge_document_id CHAR(36) NULL,
    MODIFY knowledge_chunk_id CHAR(36) NULL,
    MODIFY transcript_segment_id CHAR(36) NULL,
    ADD COLUMN source_kind VARCHAR(32) NOT NULL DEFAULT 'TRANSCRIPT_SEGMENT' AFTER knowledge_run_id,
    ADD COLUMN source_ref VARCHAR(128) NULL AFTER source_kind,
    ADD COLUMN transcription_task_id CHAR(36) NULL AFTER knowledge_document_id,
    ADD COLUMN external_label VARCHAR(512) NULL AFTER transcript_segment_id,
    ADD COLUMN external_url VARCHAR(1000) NULL AFTER external_label,
    ADD CONSTRAINT fk_knowledge_evidence_task FOREIGN KEY (transcription_task_id) REFERENCES transcription_tasks(id),
    ADD UNIQUE KEY uk_knowledge_evidence_source_ref (knowledge_run_id, result_path, source_ref);

ALTER TABLE knowledge_index_versions
    ADD COLUMN overview_document JSON NULL AFTER configuration_hash;
