ALTER TABLE transcription_tasks
    ADD COLUMN current_stage VARCHAR(48) NULL AFTER status,
    ADD COLUMN progress_percent INT NOT NULL DEFAULT 0 AFTER current_stage,
    ADD COLUMN transcript_ready BOOLEAN NOT NULL DEFAULT FALSE AFTER progress_percent,
    ADD COLUMN failed_stage VARCHAR(48) NULL AFTER failure_message;

CREATE TABLE task_stage_attempts (
    id CHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    transcription_task_id CHAR(36) NOT NULL,
    stage VARCHAR(48) NOT NULL,
    attempt_number INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    queued_at DATETIME(6) NOT NULL,
    started_at DATETIME(6),
    completed_at DATETIME(6),
    wait_duration_ms BIGINT,
    next_retry_at DATETIME(6),
    lease_until DATETIME(6),
    error_code VARCHAR(128),
    error_message VARCHAR(1000),
    result_snapshot JSON,
    CONSTRAINT fk_stage_attempt_task FOREIGN KEY (transcription_task_id) REFERENCES transcription_tasks(id),
    UNIQUE KEY uk_task_stage_attempt (transcription_task_id, stage, attempt_number),
    KEY ix_stage_attempt_retry (status, next_retry_at),
    KEY ix_stage_attempt_task (transcription_task_id, queued_at)
);
