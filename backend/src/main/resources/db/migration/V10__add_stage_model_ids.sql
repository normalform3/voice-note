ALTER TABLE task_stage_attempts
    ADD COLUMN model_id VARCHAR(128) NULL AFTER error_message;
