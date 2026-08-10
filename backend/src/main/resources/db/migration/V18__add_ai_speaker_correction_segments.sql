ALTER TABLE transcript_segments
    DROP INDEX uk_segment_version_index,
    ADD COLUMN root_segment_id CHAR(36) NULL AFTER corrected_speaker_id,
    ADD COLUMN parent_segment_id CHAR(36) NULL AFTER root_segment_id,
    ADD COLUMN source_start_offset INT NOT NULL DEFAULT 0 AFTER parent_segment_id,
    ADD COLUMN source_end_offset INT NOT NULL DEFAULT 0 AFTER source_start_offset,
    ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT TRUE AFTER source_end_offset,
    ADD COLUMN speaker_correction_source VARCHAR(16) NOT NULL DEFAULT 'ASR' AFTER is_active,
    ADD COLUMN timing_source VARCHAR(24) NOT NULL DEFAULT 'ASR' AFTER speaker_correction_source,
    ADD KEY ix_active_transcript_segments (transcription_task_id, transcript_version, is_active, segment_index, source_start_offset),
    ADD KEY ix_transcript_segment_root (root_segment_id),
    ADD KEY ix_transcript_segment_parent (parent_segment_id);

UPDATE transcript_segments
SET root_segment_id = id,
    source_end_offset = CHAR_LENGTH(text_content),
    speaker_correction_source = CASE WHEN corrected_speaker_id IS NULL THEN 'ASR' ELSE 'HUMAN' END;

ALTER TABLE transcript_segments
    MODIFY root_segment_id CHAR(36) NOT NULL;
