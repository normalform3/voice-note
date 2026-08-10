ALTER TABLE knowledge_runs
    ADD COLUMN current_checkpoint_id CHAR(36) NULL AFTER completed_at,
    ADD COLUMN parent_run_id CHAR(36) NULL AFTER current_checkpoint_id,
    ADD COLUMN root_run_id CHAR(36) NULL AFTER parent_run_id,
    ADD COLUMN replay_from_checkpoint_id CHAR(36) NULL AFTER root_run_id,
    ADD COLUMN runtime_version VARCHAR(64) NOT NULL DEFAULT 'react-runtime-v1' AFTER replay_from_checkpoint_id,
    ADD COLUMN execution_epoch BIGINT NOT NULL DEFAULT 0 AFTER runtime_version,
    ADD COLUMN recovery_count INT NOT NULL DEFAULT 0 AFTER execution_epoch,
    ADD COLUMN next_step_index INT NOT NULL DEFAULT 0 AFTER recovery_count,
    ADD COLUMN next_checkpoint_sequence INT NOT NULL DEFAULT 0 AFTER next_step_index,
    ADD COLUMN max_active_duration_ms BIGINT NOT NULL DEFAULT 120000 AFTER next_checkpoint_sequence,
    ADD COLUMN active_duration_ms BIGINT NOT NULL DEFAULT 0 AFTER max_active_duration_ms,
    ADD COLUMN failure_code VARCHAR(128) NULL AFTER failure_message,
    ADD COLUMN failure_stage VARCHAR(32) NULL AFTER failure_code;

UPDATE knowledge_runs run
SET root_run_id = id,
    runtime_version = CASE WHEN skill_version = 'legacy-v1' THEN 'legacy-v1' ELSE 'react-runtime-v1' END,
    next_step_index = (SELECT COUNT(*) FROM knowledge_run_steps step WHERE step.knowledge_run_id = run.id),
    active_duration_ms = COALESCE((SELECT SUM(step.duration_ms) FROM knowledge_run_steps step WHERE step.knowledge_run_id = run.id), 0);

ALTER TABLE knowledge_runs
    ADD CONSTRAINT fk_knowledge_run_parent FOREIGN KEY (parent_run_id) REFERENCES knowledge_runs(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_knowledge_run_root FOREIGN KEY (root_run_id) REFERENCES knowledge_runs(id) ON DELETE SET NULL,
    ADD KEY ix_knowledge_run_parent (parent_run_id, created_at),
    ADD KEY ix_knowledge_run_root (root_run_id, created_at);

ALTER TABLE knowledge_run_steps
    ADD COLUMN execution_epoch BIGINT NOT NULL DEFAULT 0 AFTER status,
    ADD COLUMN input_checkpoint_id CHAR(36) NULL AFTER execution_epoch,
    ADD COLUMN output_checkpoint_id CHAR(36) NULL AFTER input_checkpoint_id,
    ADD COLUMN finish_reason VARCHAR(64) NULL AFTER duration_ms,
    ADD COLUMN input_tokens INT NULL AFTER finish_reason,
    ADD COLUMN output_tokens INT NULL AFTER input_tokens,
    ADD COLUMN total_tokens INT NULL AFTER output_tokens;

CREATE TABLE agent_checkpoints (
    id CHAR(36) PRIMARY KEY,
    knowledge_run_id CHAR(36) NOT NULL,
    checkpoint_sequence INT NOT NULL,
    state_schema_version INT NOT NULL,
    runtime_version VARCHAR(64) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    step_id CHAR(36) NULL,
    state_document JSON NOT NULL,
    state_hash CHAR(64) NOT NULL,
    replayable BOOLEAN NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_agent_checkpoint_run FOREIGN KEY (knowledge_run_id) REFERENCES knowledge_runs(id),
    UNIQUE KEY uk_agent_checkpoint_sequence (knowledge_run_id, checkpoint_sequence),
    KEY ix_agent_checkpoint_step (knowledge_run_id, step_id),
    KEY ix_agent_checkpoint_replay (knowledge_run_id, replayable, checkpoint_sequence)
);
