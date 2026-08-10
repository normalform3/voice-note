package com.voicenote.service;

import com.voicenote.domain.*;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Derives question-answering capabilities without conflating transcript, formal-document, and index state. */
@Component
public class DocumentQaPolicy {
    public Capabilities evaluate(TranscriptionTask task, OrganizedDocument organized,
                                 KnowledgeDocument document, KnowledgeIndexVersion indexVersion) {
        if (task == null || !task.isTranscriptReady()) {
            return new Capabilities(false, null, false, "TRANSCRIPT_NOT_READY");
        }
        if (hasActiveIndex(task, document, indexVersion)) {
            return new Capabilities(true, QaRetrievalMode.HYBRID_INDEX, true, null);
        }
        if (hasReadyFormalDocument(task, organized)) {
            return new Capabilities(true, QaRetrievalMode.FORMAL_OVERVIEW, false, "KNOWLEDGE_INDEX_NOT_ACTIVE");
        }
        return new Capabilities(true, QaRetrievalMode.TRANSCRIPT_LOCAL, false, "FORMAL_DOCUMENT_NOT_READY");
    }

    public boolean hasReadyFormalDocument(TranscriptionTask task, OrganizedDocument organized) {
        return task != null && organized != null
                && organized.getStatus() == OrganizedDocumentStatus.READY
                && organized.getOwnerId().equals(task.getOwnerId())
                && organized.getTranscriptionTaskId().equals(task.getId())
                && organized.getTranscriptVersion() == task.getTranscriptVersion();
    }

    public boolean hasActiveIndex(TranscriptionTask task, KnowledgeDocument document, KnowledgeIndexVersion indexVersion) {
        return task != null && document != null && indexVersion != null
                && document.getOwnerId().equals(task.getOwnerId())
                && document.getTranscriptionTaskId().equals(task.getId())
                && document.getTranscriptVersion() == task.getTranscriptVersion()
                && document.getStatus() == KnowledgeDocumentStatus.READY
                && Objects.equals(document.getActiveIndexVersionId(), indexVersion.getId())
                && Objects.equals(indexVersion.getKnowledgeDocumentId(), document.getId())
                && indexVersion.getStatus() == KnowledgeIndexVersionStatus.READY
                && indexVersion.isActive();
    }

    public record Capabilities(boolean currentDocumentAvailable, QaRetrievalMode currentMode,
                               boolean crossDocumentEligible, String limitationCode) { }
}
