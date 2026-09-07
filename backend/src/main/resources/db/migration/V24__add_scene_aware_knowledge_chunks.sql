ALTER TABLE knowledge_chunks
    ADD COLUMN chunk_profile VARCHAR(32) NOT NULL DEFAULT 'MONOLOGUE' AFTER context_segment_ids,
    ADD COLUMN chunk_kind VARCHAR(32) NOT NULL DEFAULT 'NARRATIVE' AFTER chunk_profile,
    ADD COLUMN dense_text MEDIUMTEXT NULL AFTER text_content,
    ADD COLUMN lexical_text MEDIUMTEXT NULL AFTER dense_text;

UPDATE knowledge_chunks
SET dense_text = text_content,
    lexical_text = text_content
WHERE dense_text IS NULL OR lexical_text IS NULL;

ALTER TABLE knowledge_chunks
    MODIFY dense_text MEDIUMTEXT NOT NULL,
    MODIFY lexical_text MEDIUMTEXT NOT NULL;
