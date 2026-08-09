ALTER TABLE transcription_tasks
    ADD COLUMN speaker_correction_revision INT NOT NULL DEFAULT 0 AFTER transcript_version;

ALTER TABLE transcript_segments
    ADD COLUMN corrected_speaker_id VARCHAR(128) NULL AFTER asr_speaker_id;

