ALTER TABLE transcription_tasks
    ADD COLUMN asr_config JSON NULL AFTER asr_config_hash;

ALTER TABLE transcript_segments
    ADD COLUMN asr_speaker_id VARCHAR(128) NULL AFTER speaker_label;

UPDATE transcript_segments
SET asr_speaker_id = COALESCE(NULLIF(speaker_label, ''), 'SPEAKER_UNKNOWN')
WHERE asr_speaker_id IS NULL;

CREATE TABLE transcript_speakers (
    id CHAR(36) PRIMARY KEY,
    transcription_task_id CHAR(36) NOT NULL,
    transcript_version INT NOT NULL,
    asr_speaker_id VARCHAR(128) NOT NULL,
    suggested_role VARCHAR(32) NOT NULL,
    suggested_confidence DOUBLE NULL,
    confirmed_role VARCHAR(32) NULL,
    display_name VARCHAR(128) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_transcript_speaker_task FOREIGN KEY (transcription_task_id) REFERENCES transcription_tasks(id),
    UNIQUE KEY uk_transcript_speaker (transcription_task_id, transcript_version, asr_speaker_id),
    KEY ix_transcript_speaker_task (transcription_task_id, transcript_version)
);

ALTER TABLE organized_documents
    ADD COLUMN summary_text TEXT NULL AFTER title,
    ADD COLUMN organization_mode VARCHAR(32) NOT NULL DEFAULT 'RULES' AFTER summary_text;

ALTER TABLE organized_document_blocks
    ADD COLUMN parent_block_id CHAR(36) NULL AFTER block_type,
    ADD COLUMN speaker_ids JSON NULL AFTER speaker_label,
    ADD COLUMN summary_text TEXT NULL AFTER topic_title,
    ADD COLUMN source_fragments JSON NULL AFTER source_segment_ids,
    ADD KEY ix_organized_block_parent (organized_document_id, parent_block_id, block_index);

ALTER TABLE knowledge_chunks
    ADD COLUMN topic_title VARCHAR(512) NULL AFTER organized_document_block_ids,
    ADD COLUMN speaker_ids JSON NULL AFTER topic_title,
    ADD COLUMN source_fragments JSON NULL AFTER speaker_ids,
    ADD COLUMN context_segment_ids JSON NULL AFTER source_fragments,
    ADD COLUMN token_count INT NULL AFTER context_segment_ids,
    ADD COLUMN oversized BOOLEAN NOT NULL DEFAULT FALSE AFTER token_count;

CREATE TABLE organization_invocations (
    id CHAR(36) PRIMARY KEY,
    organized_document_id CHAR(36) NOT NULL,
    stage_name VARCHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    response_document JSON NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_organization_invocation_document FOREIGN KEY (organized_document_id) REFERENCES organized_documents(id),
    UNIQUE KEY uk_organization_invocation_stage (organized_document_id, stage_name)
);
