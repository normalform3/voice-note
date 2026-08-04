package com.voicenote.domain;

public enum PipelineStage {
    UPLOAD_COMPLETED,
    ASR_SUBMIT,
    ASR_POLL,
    TRANSCRIPT_PERSIST,
    DOCUMENT_ORGANIZATION,
    KNOWLEDGE_PREPARE,
    KNOWLEDGE_INDEX,
    COMPLETED
}
