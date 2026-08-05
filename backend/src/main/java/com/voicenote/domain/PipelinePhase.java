package com.voicenote.domain;

/** The three user-visible, restartable processing phases. */
public enum PipelinePhase {
    TRANSCRIPTION,
    RAW_DOCUMENT_REVIEW,
    DOCUMENT_ORGANIZATION,
    FORMAL_DOCUMENT_REVIEW,
    KNOWLEDGE_BUILD,
    COMPLETED
}
