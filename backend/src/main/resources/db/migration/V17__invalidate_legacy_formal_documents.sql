UPDATE transcription_tasks task
JOIN organized_documents document ON document.transcription_task_id = task.id
SET task.version = task.version + 1,
    task.status = 'WAITING_FOR_FORMAL_DOCUMENT',
    task.current_stage = 'RAW_DOCUMENT_READY',
    task.current_phase = 'RAW_DOCUMENT_REVIEW',
    task.progress_percent = 60,
    task.failure_code = NULL,
    task.failure_message = NULL,
    task.failed_stage = NULL,
    task.updated_at = CURRENT_TIMESTAMP(6)
WHERE document.status = 'READY'
  AND task.transcript_ready = TRUE
  AND task.status <> 'CANCELLED';

UPDATE analysis_runs analysis
JOIN organized_documents document ON document.id = analysis.organized_document_id
SET analysis.status = 'STALE',
    analysis.updated_at = CURRENT_TIMESTAMP(6)
WHERE document.status = 'READY'
  AND analysis.status <> 'STALE';

UPDATE knowledge_index_versions index_version
JOIN knowledge_documents knowledge_document ON knowledge_document.id = index_version.knowledge_document_id
JOIN organized_documents document ON document.id = knowledge_document.organized_document_id
SET index_version.version = index_version.version + 1,
    index_version.status = 'RETIRED',
    index_version.active = FALSE,
    index_version.updated_at = CURRENT_TIMESTAMP(6)
WHERE document.status = 'READY'
  AND index_version.active = FALSE
  AND index_version.status IN ('PENDING', 'QUEUED', 'INDEXING', 'FAILED');

UPDATE knowledge_documents knowledge_document
JOIN organized_documents document ON document.id = knowledge_document.organized_document_id
SET knowledge_document.version = knowledge_document.version + 1,
    knowledge_document.status = 'STALE',
    knowledge_document.failure_message = NULL,
    knowledge_document.updated_at = CURRENT_TIMESTAMP(6)
WHERE document.status = 'READY';

UPDATE organized_documents
SET version = version + 1,
    status = 'STALE',
    failure_message = NULL,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE status = 'READY';
