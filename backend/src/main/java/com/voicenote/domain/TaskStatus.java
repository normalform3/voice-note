package com.voicenote.domain;

public enum TaskStatus {
    QUEUED, RUNNING, RETRY_WAIT, FAILED, CANCELLED, SUCCEEDED,
    WAITING_FOR_FORMAL_DOCUMENT, WAITING_FOR_KNOWLEDGE_BUILD,
    /** Compatibility values retained only while old rows are migrated. */
    SUBMITTING, PROVIDER_RUNNING, INDEXING, RETRYABLE_FAILED, FINAL_FAILED, SUBMISSION_UNKNOWN
}
