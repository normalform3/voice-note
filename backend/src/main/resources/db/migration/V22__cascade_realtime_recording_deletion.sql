ALTER TABLE realtime_recording_parts
    DROP FOREIGN KEY fk_realtime_recording_part_session;

ALTER TABLE realtime_recording_parts
    ADD CONSTRAINT fk_realtime_recording_part_session
        FOREIGN KEY (session_id) REFERENCES realtime_recording_sessions(id) ON DELETE CASCADE;

ALTER TABLE realtime_recording_sessions
    DROP FOREIGN KEY fk_realtime_recording_task;

ALTER TABLE realtime_recording_sessions
    ADD CONSTRAINT fk_realtime_recording_task
        FOREIGN KEY (transcription_task_id) REFERENCES transcription_tasks(id) ON DELETE CASCADE;
