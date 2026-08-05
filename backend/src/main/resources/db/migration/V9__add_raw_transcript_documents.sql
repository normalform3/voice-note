CREATE TABLE raw_transcript_documents (
    id CHAR(36) PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    transcription_task_id CHAR(36) NOT NULL,
    transcript_version INT NOT NULL,
    provider_task_id VARCHAR(255) NOT NULL,
    result_object_key VARCHAR(1024) NOT NULL,
    result_sha256 CHAR(64) NOT NULL,
    content_text MEDIUMTEXT NOT NULL,
    segment_count INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_raw_transcript_task FOREIGN KEY (transcription_task_id) REFERENCES transcription_tasks(id),
    UNIQUE KEY uk_raw_transcript_source (owner_id, transcription_task_id, transcript_version)
);
