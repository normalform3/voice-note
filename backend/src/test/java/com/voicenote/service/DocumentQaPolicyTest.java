package com.voicenote.service;

import com.voicenote.domain.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentQaPolicyTest {
    private final DocumentQaPolicy policy = new DocumentQaPolicy();

    @Test
    void derivesTranscriptFormalAndIndexedCapabilitiesIndependently() {
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "pipeline");
        assertThat(policy.evaluate(task, null, null, null).currentDocumentAvailable()).isFalse();

        task.transcriptPersisted();
        assertThat(policy.evaluate(task, null, null, null))
                .extracting(DocumentQaPolicy.Capabilities::currentMode, DocumentQaPolicy.Capabilities::crossDocumentEligible)
                .containsExactly(QaRetrievalMode.TRANSCRIPT_LOCAL, false);

        OrganizedDocument organized = new OrganizedDocument("owner", task.getId(), task.getTranscriptVersion(), "正式文档");
        organized.ready("{}", "正式内容");
        assertThat(policy.evaluate(task, organized, null, null).currentMode()).isEqualTo(QaRetrievalMode.FORMAL_OVERVIEW);

        KnowledgeDocument document = new KnowledgeDocument("owner", task.getId(), task.getTranscriptVersion(), "知识文档",
                organized.getId(), Math.toIntExact(organized.getVersion()));
        KnowledgeIndexVersion index = new KnowledgeIndexVersion(document.getId(), 1, organized.getId(), organized.getVersion(), "b".repeat(64));
        index.ready(); index.activate(); document.activateIndexVersion(index.getId());
        DocumentQaPolicy.Capabilities indexed = policy.evaluate(task, organized, document, index);

        assertThat(indexed.currentMode()).isEqualTo(QaRetrievalMode.HYBRID_INDEX);
        assertThat(indexed.crossDocumentEligible()).isTrue();
        assertThat(indexed.limitationCode()).isNull();
    }

    @Test
    void invalidatedDerivedContentFallsBackToTheCurrentTranscript() {
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "pipeline"); task.transcriptPersisted();
        OrganizedDocument organized = new OrganizedDocument("owner", task.getId(), task.getTranscriptVersion(), "正式文档"); organized.ready("{}", "内容");
        KnowledgeDocument document = new KnowledgeDocument("owner", task.getId(), task.getTranscriptVersion(), "知识文档", organized.getId(), 0);
        KnowledgeIndexVersion index = new KnowledgeIndexVersion(document.getId(), 1, organized.getId(), 0, "b".repeat(64));
        index.ready(); index.activate(); document.activateIndexVersion(index.getId());

        organized.stale(); document.stale(); index.retire();

        DocumentQaPolicy.Capabilities capabilities = policy.evaluate(task, organized, document, index);
        assertThat(capabilities.currentMode()).isEqualTo(QaRetrievalMode.TRANSCRIPT_LOCAL);
        assertThat(capabilities.crossDocumentEligible()).isFalse();
    }
}
