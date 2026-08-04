package com.voicenote.domain;

/** The three user-visible, restartable processing phases. */
public enum PipelinePhase {
    TRANSCRIPTION,
    DOCUMENT_ORGANIZATION,
    KNOWLEDGE_BUILD,
    COMPLETED
}
