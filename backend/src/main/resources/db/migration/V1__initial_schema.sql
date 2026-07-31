CREATE TABLE users (
    id CHAR(36) PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_users_email (email)
);

CREATE TABLE audio_blobs (
    id CHAR(36) PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    content_length BIGINT NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    original_filename VARCHAR(512) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    status VARCHAR(32) NOT NULL,
    failure_reason VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6),
    CONSTRAINT fk_audio_blobs_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    UNIQUE KEY uk_audio_blobs_owner_hash (owner_id, sha256)
);

CREATE TABLE idempotency_records (
    id CHAR(36) PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    operation_name VARCHAR(96) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    resource_id CHAR(36),
    response_status INT,
    response_body JSON,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_idempotency_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    UNIQUE KEY uk_idempotency_owner_operation_key (owner_id, operation_name, idempotency_key)
);

CREATE TABLE transcription_tasks (
    id CHAR(36) PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    audio_blob_id CHAR(36) NOT NULL,
    asr_config_hash CHAR(64) NOT NULL,
    pipeline_version VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_attempt_number INT NOT NULL DEFAULT 0,
    transcript_version INT NOT NULL DEFAULT 0,
    failure_code VARCHAR(128),
    failure_message VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_tasks_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    CONSTRAINT fk_tasks_blob FOREIGN KEY (audio_blob_id) REFERENCES audio_blobs(id),
    UNIQUE KEY uk_transcription_task_semantic (owner_id, audio_blob_id, asr_config_hash, pipeline_version)
);

CREATE TABLE task_attempts (
    id CHAR(36) PRIMARY KEY,
    transcription_task_id CHAR(36) NOT NULL,
    attempt_number INT NOT NULL,
    status VARCHAR(40) NOT NULL,
    provider_task_id VARCHAR(255),
    provider_input_url VARCHAR(2048),
    next_poll_at DATETIME(6),
    error_code VARCHAR(128),
    error_message VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_attempt_task FOREIGN KEY (transcription_task_id) REFERENCES transcription_tasks(id),
    UNIQUE KEY uk_task_attempt_number (transcription_task_id, attempt_number),
    UNIQUE KEY uk_provider_task_id (provider_task_id)
);

CREATE TABLE provider_invocations (
    id CHAR(36) PRIMARY KEY,
    task_attempt_id CHAR(36) NOT NULL,
    invocation_type VARCHAR(48) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    lease_until DATETIME(6),
    response_snapshot JSON,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_invocation_attempt FOREIGN KEY (task_attempt_id) REFERENCES task_attempts(id),
    UNIQUE KEY uk_invocation_attempt_type (task_attempt_id, invocation_type)
);

CREATE TABLE transcript_segments (
    id CHAR(36) PRIMARY KEY,
    transcription_task_id CHAR(36) NOT NULL,
    transcript_version INT NOT NULL,
    segment_index INT NOT NULL,
    speaker_label VARCHAR(128),
    start_ms BIGINT NOT NULL,
    end_ms BIGINT NOT NULL,
    text_content TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_segments_task FOREIGN KEY (transcription_task_id) REFERENCES transcription_tasks(id),
    UNIQUE KEY uk_segment_version_index (transcription_task_id, transcript_version, segment_index)
);

CREATE TABLE analysis_runs (
    id CHAR(36) PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    transcription_task_id CHAR(36) NOT NULL,
    transcript_snapshot_hash CHAR(64) NOT NULL,
    analysis_mode VARCHAR(48) NOT NULL,
    custom_goal TEXT NOT NULL,
    template_version VARCHAR(32) NOT NULL,
    model_id VARCHAR(128) NOT NULL,
    semantic_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    repair_rounds INT NOT NULL DEFAULT 0,
    max_calls INT NOT NULL,
    calls_used INT NOT NULL DEFAULT 0,
    result_document JSON,
    quality_status VARCHAR(48),
    failure_message VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_analysis_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    CONSTRAINT fk_analysis_task FOREIGN KEY (transcription_task_id) REFERENCES transcription_tasks(id),
    UNIQUE KEY uk_analysis_semantic (owner_id, transcription_task_id, semantic_hash)
);

CREATE TABLE analysis_evidence (
    id CHAR(36) PRIMARY KEY,
    analysis_run_id CHAR(36) NOT NULL,
    result_path VARCHAR(512) NOT NULL,
    transcript_segment_id CHAR(36) NOT NULL,
    start_offset INT,
    end_offset INT,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_evidence_run FOREIGN KEY (analysis_run_id) REFERENCES analysis_runs(id),
    CONSTRAINT fk_evidence_segment FOREIGN KEY (transcript_segment_id) REFERENCES transcript_segments(id),
    UNIQUE KEY uk_evidence_path_segment (analysis_run_id, result_path, transcript_segment_id)
);

CREATE TABLE analysis_invocations (
    id CHAR(36) PRIMARY KEY,
    analysis_run_id CHAR(36) NOT NULL,
    stage_name VARCHAR(48) NOT NULL,
    chunk_index INT NOT NULL,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    response_document JSON,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_analysis_invocation_run FOREIGN KEY (analysis_run_id) REFERENCES analysis_runs(id),
    UNIQUE KEY uk_analysis_invocation_stage (analysis_run_id, stage_name, chunk_index)
);

CREATE TABLE outbox_events (
    id CHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id CHAR(36) NOT NULL,
    event_type VARCHAR(96) NOT NULL,
    payload JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL,
    published_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    KEY ix_outbox_ready (status, available_at)
);

CREATE TABLE inbox_messages (
    id CHAR(36) PRIMARY KEY,
    consumer_name VARCHAR(96) NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    received_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_inbox_consumer_message (consumer_name, message_id)
);
