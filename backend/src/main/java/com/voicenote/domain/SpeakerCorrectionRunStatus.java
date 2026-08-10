package com.voicenote.domain;

public enum SpeakerCorrectionRunStatus {
    QUEUED,
    RUNNING,
    READY,
    APPLIED,
    FAILED,
    STALE
}
