package com.voicenote.service;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.PipelineStage;
import com.voicenote.provider.ProviderException;
import org.springframework.stereotype.Component;

@Component
public class OrganizedDocumentWorker {
    private final AppProperties properties;
    private final DocumentOrganizationService documents;
    private final KnowledgeDocumentService knowledge;
    private final PipelineProgressService pipeline;

    public OrganizedDocumentWorker(AppProperties properties, DocumentOrganizationService documents, KnowledgeDocumentService knowledge, PipelineProgressService pipeline) {
        this.properties = properties; this.documents = documents; this.knowledge = knowledge; this.pipeline = pipeline;
    }

    /** Invoked after the RocketMQ consumer commits the durable QUEUED transition. */
    public void process(String documentId) { if (properties.getWorkers().isEnabled()) organize(documentId); }
    /** Recovery-only sweep for a process crash between consumer commit and work handoff. */
    public void recoverQueued() { if (properties.getWorkers().isEnabled()) documents.queuedDocumentIds().forEach(this::organize); }

    private void organize(String documentId) {
        DocumentOrganizationService.OrganizationWork work = documents.claim(documentId);
        if (work == null || !pipeline.begin(work.document().getTranscriptionTaskId(), PipelineStage.DOCUMENT_ORGANIZATION)) return;
        try {
            DocumentOrganizationService.OrganizationResult result = DocumentOrganizationService.organize(work.segments());
            var blocks = documents.complete(documentId, result);
            if (blocks.isEmpty()) return;
            pipeline.succeeded(work.document().getTranscriptionTaskId(), PipelineStage.DOCUMENT_ORGANIZATION,
                    "{\"documentId\":\"" + documentId + "\",\"turnCount\":" + result.turns().size() + ",\"topicCount\":" + result.topics().size() + "}", PipelineStage.KNOWLEDGE_PREPARE);
            if (!pipeline.begin(work.document().getTranscriptionTaskId(), PipelineStage.KNOWLEDGE_PREPARE)) return;
            knowledge.createForOrganizedDocument(work.document(), blocks);
            pipeline.succeeded(work.document().getTranscriptionTaskId(), PipelineStage.KNOWLEDGE_PREPARE,
                    "{\"organizedDocumentId\":\"" + documentId + "\"}", PipelineStage.KNOWLEDGE_INDEX);
        } catch (ProviderException exception) {
            documents.fail(documentId, exception.getCode() + ": " + exception.getMessage());
            pipeline.failed(work.document().getTranscriptionTaskId(), PipelineStage.DOCUMENT_ORGANIZATION, exception.getCode(), exception.getMessage(), false);
        } catch (RuntimeException exception) {
            documents.fail(documentId, "DOCUMENT_ORGANIZATION_FAILED: " + exception.getMessage());
            pipeline.failed(work.document().getTranscriptionTaskId(), PipelineStage.DOCUMENT_ORGANIZATION, "DOCUMENT_ORGANIZATION_FAILED", exception.getMessage(), false);
        }
    }
}
