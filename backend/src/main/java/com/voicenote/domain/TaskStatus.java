package com.voicenote.domain;

public enum TaskStatus {
    QUEUED, RUNNING, RETRY_WAIT, FAILED, CANCELLED, SUCCEEDED,
    /** Compatibility values retained only while old rows are migrated. */
    SUBMITTING, PROVIDER_RUNNING, INDEXING, RETRYABLE_FAILED, FINAL_FAILED, SUBMISSION_UNKNOWN
}
