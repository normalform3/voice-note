package com.voicenote.domain;

public enum KnowledgeRunStatus {
    PENDING,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    BUDGET_EXHAUSTED,
    TIMED_OUT
}
