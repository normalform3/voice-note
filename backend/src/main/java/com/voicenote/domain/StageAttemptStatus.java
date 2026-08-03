package com.voicenote.domain;

public enum StageAttemptStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    RETRY_WAIT,
    RETRIED,
    FAILED,
    UNKNOWN
}
