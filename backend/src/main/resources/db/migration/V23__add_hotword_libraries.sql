CREATE TABLE hotword_capacity (
    id INT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0
);

INSERT INTO hotword_capacity (id, version) VALUES (1, 0);

CREATE TABLE hotword_libraries (
    id CHAR(36) PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    entries JSON NOT NULL,
    content_hash CHAR(64) NOT NULL,
    revision INT NOT NULL DEFAULT 0,
    provider_prefix VARCHAR(10) NOT NULL,
    provider_vocabulary_id VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(128),
    error_message VARCHAR(1000),
    provider_synced_at DATETIME(6),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_hotword_library_owner FOREIGN KEY (owner_id) REFERENCES users(id),
    UNIQUE KEY uk_hotword_provider_prefix (provider_prefix),
    UNIQUE KEY uk_hotword_provider_vocabulary (provider_vocabulary_id),
    KEY ix_hotword_owner_updated (owner_id, updated_at)
);

ALTER TABLE transcription_tasks
    ADD COLUMN hotword_library_id CHAR(36),
    ADD COLUMN hotword_library_revision INT,
    ADD CONSTRAINT fk_task_hotword_library FOREIGN KEY (hotword_library_id) REFERENCES hotword_libraries(id),
    ADD KEY ix_task_hotword_library (hotword_library_id);

ALTER TABLE realtime_recording_sessions
    ADD COLUMN hotword_library_id CHAR(36),
    ADD COLUMN hotword_library_revision INT,
    ADD CONSTRAINT fk_realtime_hotword_library FOREIGN KEY (hotword_library_id) REFERENCES hotword_libraries(id),
    ADD KEY ix_realtime_hotword_library (hotword_library_id);
