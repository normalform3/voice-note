CREATE TABLE agent_conversations (
    id CHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    owner_id CHAR(36) NOT NULL,
    title VARCHAR(160) NOT NULL,
    status VARCHAR(32) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    skill_id VARCHAR(128) NOT NULL,
    skill_version VARCHAR(64) NOT NULL,
    skill_version_id CHAR(36) NULL,
    skill_snapshot JSON NULL,
    skill_hash CHAR(64) NULL,
    memory_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    rolling_summary MEDIUMTEXT NULL,
    summary_through_turn INT NOT NULL DEFAULT -1,
    summary_status VARCHAR(32) NOT NULL DEFAULT 'IDLE',
    summary_attempts INT NOT NULL DEFAULT 0,
    summary_input_hash CHAR(64) NULL,
    summary_prompt_version VARCHAR(64) NULL,
    summary_model_id VARCHAR(128) NULL,
    summary_duration_ms BIGINT NULL,
    summary_failure_code VARCHAR(128) NULL,
    summary_failure_message VARCHAR(1000) NULL,
    next_turn_index INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_agent_conversation_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    KEY ix_agent_conversation_owner (owner_id, status, updated_at)
);

CREATE TABLE agent_conversation_documents (
    id CHAR(36) PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    transcription_task_id CHAR(36) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_agent_conversation_document_conversation FOREIGN KEY (conversation_id) REFERENCES agent_conversations(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_conversation_document_task FOREIGN KEY (transcription_task_id) REFERENCES transcription_tasks(id),
    UNIQUE KEY uk_agent_conversation_document (conversation_id, transcription_task_id),
    KEY ix_agent_conversation_document_task (transcription_task_id, conversation_id)
);

CREATE TABLE agent_conversation_turns (
    id CHAR(36) PRIMARY KEY,
    conversation_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    turn_index INT NOT NULL,
    user_message TEXT NOT NULL,
    knowledge_run_id CHAR(36) NULL,
    extraction_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED',
    extraction_attempts INT NOT NULL DEFAULT 0,
    extraction_input_hash CHAR(64) NULL,
    extraction_prompt_version VARCHAR(64) NULL,
    extraction_model_id VARCHAR(128) NULL,
    extraction_duration_ms BIGINT NULL,
    extraction_failure_code VARCHAR(128) NULL,
    extraction_failure_message VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_agent_conversation_turn_conversation FOREIGN KEY (conversation_id) REFERENCES agent_conversations(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_conversation_turn_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    UNIQUE KEY uk_agent_conversation_turn (conversation_id, turn_index),
    UNIQUE KEY uk_agent_conversation_turn_run (knowledge_run_id),
    KEY ix_agent_conversation_turn_extraction (extraction_status, updated_at)
);

ALTER TABLE knowledge_runs
    ADD COLUMN conversation_id CHAR(36) NULL AFTER owner_id,
    ADD COLUMN conversation_turn_index INT NULL AFTER conversation_id,
    ADD COLUMN memory_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER conversation_turn_index,
    ADD CONSTRAINT fk_knowledge_run_conversation FOREIGN KEY (conversation_id) REFERENCES agent_conversations(id),
    ADD UNIQUE KEY uk_knowledge_run_conversation_turn (conversation_id, conversation_turn_index),
    ADD KEY ix_knowledge_run_conversation (conversation_id, created_at);

ALTER TABLE agent_conversation_turns
    ADD CONSTRAINT fk_agent_conversation_turn_run FOREIGN KEY (knowledge_run_id) REFERENCES knowledge_runs(id) ON DELETE SET NULL;

CREATE TABLE user_memories (
    id CHAR(36) PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    owner_id CHAR(36) NOT NULL,
    category VARCHAR(32) NOT NULL,
    semantic_key VARCHAR(160) NOT NULL,
    current_version_id CHAR(36) NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_user_memory_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    UNIQUE KEY uk_user_memory_semantic_key (owner_id, semantic_key),
    KEY ix_user_memory_owner (owner_id, status, updated_at)
);

CREATE TABLE user_memory_candidates (
    id CHAR(36) PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    source_turn_id CHAR(36) NULL,
    category VARCHAR(32) NOT NULL,
    semantic_key VARCHAR(160) NOT NULL,
    content TEXT NOT NULL,
    source_excerpt VARCHAR(2000) NOT NULL,
    confidence DECIMAL(5,4) NOT NULL,
    change_type VARCHAR(32) NOT NULL,
    target_memory_id CHAR(36) NULL,
    status VARCHAR(32) NOT NULL,
    extraction_version VARCHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_user_memory_candidate_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    CONSTRAINT fk_user_memory_candidate_turn FOREIGN KEY (source_turn_id) REFERENCES agent_conversation_turns(id) ON DELETE SET NULL,
    CONSTRAINT fk_user_memory_candidate_target FOREIGN KEY (target_memory_id) REFERENCES user_memories(id) ON DELETE SET NULL,
    UNIQUE KEY uk_user_memory_candidate_turn_key (source_turn_id, semantic_key),
    KEY ix_user_memory_candidate_owner (owner_id, status, created_at)
);

CREATE TABLE user_memory_versions (
    id CHAR(36) PRIMARY KEY,
    memory_id CHAR(36) NOT NULL,
    version_number INT NOT NULL,
    content TEXT NOT NULL,
    source_candidate_id CHAR(36) NULL,
    index_status VARCHAR(32) NOT NULL,
    index_attempts INT NOT NULL DEFAULT 0,
    index_failure_message VARCHAR(1000) NULL,
    confirmed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_user_memory_version_memory FOREIGN KEY (memory_id) REFERENCES user_memories(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_memory_version_candidate FOREIGN KEY (source_candidate_id) REFERENCES user_memory_candidates(id) ON DELETE SET NULL,
    UNIQUE KEY uk_user_memory_version_number (memory_id, version_number),
    KEY ix_user_memory_version_index (index_status, created_at)
);

ALTER TABLE user_memories
    ADD CONSTRAINT fk_user_memory_current_version FOREIGN KEY (current_version_id) REFERENCES user_memory_versions(id) ON DELETE SET NULL;

CREATE TABLE user_memory_deletions (
    id CHAR(36) PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    memory_id CHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    failure_message VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_user_memory_deletion_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    KEY ix_user_memory_deletion_status (status, updated_at)
);

ALTER TABLE knowledge_run_sources
    ADD COLUMN user_memory_id CHAR(36) NULL AFTER transcript_segment_id,
    ADD COLUMN user_memory_version_id CHAR(36) NULL AFTER user_memory_id;

ALTER TABLE knowledge_run_evidence
    ADD COLUMN user_memory_id CHAR(36) NULL AFTER transcript_segment_id,
    ADD COLUMN user_memory_version_id CHAR(36) NULL AFTER user_memory_id,
    ADD COLUMN user_memory_content_snapshot TEXT NULL AFTER user_memory_version_id;
