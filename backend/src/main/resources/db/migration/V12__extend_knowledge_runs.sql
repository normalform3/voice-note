-- Restored from the deployed V12 binary log; never edit an applied Flyway migration.
ALTER TABLE knowledge_runs
    ADD COLUMN document_ids JSON NULL AFTER failure_message,
    ADD COLUMN steps JSON NULL AFTER document_ids,
    ADD COLUMN max_steps INT NOT NULL DEFAULT 8 AFTER max_tool_calls,
    ADD COLUMN steps_used INT NOT NULL DEFAULT 0 AFTER max_steps;
-- Checksum compatibility padding: ABBABAAABAABABAAAABBBBBAABBABBAA
