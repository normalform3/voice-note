package com.voicenote.service;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.PipelineStage;
import com.voicenote.provider.AnalysisModelClient;
import com.voicenote.provider.ProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OrganizedDocumentWorker {
    private static final Logger log = LoggerFactory.getLogger(OrganizedDocumentWorker.class);
    private final AppProperties properties;
    private final DocumentOrganizationService documents;
    private final PipelineProgressService pipeline;
    private final AnalysisModelClient model;

    public OrganizedDocumentWorker(AppProperties properties, DocumentOrganizationService documents, PipelineProgressService pipeline, AnalysisModelClient model) {
        this.properties = properties; this.documents = documents; this.pipeline = pipeline; this.model = model;
    }

    /** Invoked after the RocketMQ consumer commits the durable QUEUED transition. */
    public void process(String documentId) { if (properties.getWorkers().isEnabled()) organize(documentId); }
    /** Recovery-only sweep for a process crash between consumer commit and work handoff. */
    public void recoverQueued() { if (properties.getWorkers().isEnabled()) documents.queuedDocumentIds().forEach(this::organize); }

    private void organize(String documentId) {
        DocumentOrganizationService.OrganizationWork work = documents.claim(documentId);
        if (work == null || !pipeline.begin(work.document().getTranscriptionTaskId(), PipelineStage.DOCUMENT_ORGANIZATION)) return;
        try {
            DocumentOrganizationService.OrganizationResult result = organize(work);
            var blocks = documents.complete(documentId, result, work.segments());
            if (blocks.isEmpty()) return;
            pipeline.succeeded(work.document().getTranscriptionTaskId(), PipelineStage.DOCUMENT_ORGANIZATION,
                    "{\"documentId\":\"" + documentId + "\",\"turnCount\":" + result.turns().size() + ",\"topicCount\":" + result.topics().size() + "}", PipelineStage.FORMAL_DOCUMENT_READY);
            if (pipeline.begin(work.document().getTranscriptionTaskId(), PipelineStage.FORMAL_DOCUMENT_READY)) {
                pipeline.succeeded(work.document().getTranscriptionTaskId(), PipelineStage.FORMAL_DOCUMENT_READY, "{\"documentId\":\"" + documentId + "\"}", null);
            }
            pipeline.awaitKnowledgeBuild(work.document().getTranscriptionTaskId());
        } catch (ProviderException exception) {
            documents.fail(documentId, exception.getCode() + ": " + exception.getMessage());
            pipeline.failed(work.document().getTranscriptionTaskId(), PipelineStage.DOCUMENT_ORGANIZATION, exception.getCode(), exception.getMessage(), false);
        } catch (RuntimeException exception) {
            documents.fail(documentId, "DOCUMENT_ORGANIZATION_FAILED: " + exception.getMessage());
            pipeline.failed(work.document().getTranscriptionTaskId(), PipelineStage.DOCUMENT_ORGANIZATION, "DOCUMENT_ORGANIZATION_FAILED", exception.getMessage(), false);
        }
    }

    private DocumentOrganizationService.OrganizationResult organize(DocumentOrganizationService.OrganizationWork work) {
        try {
            DocumentOrganizationService.ModelAction action = documents.prepareSemantic(work);
            if (!action.cached() && properties.getDashscope().isEnabled()) {
                pipeline.recordModelInvocation(work.document().getTranscriptionTaskId(), PipelineStage.DOCUMENT_ORGANIZATION, properties.getDashscope().getChatModel());
            }
            String response = action.cached() ? action.value() : model.complete(action.value());
            DocumentOrganizationService.OrganizationResult result = documents.organizeSemantic(work, response);
            if (!action.cached()) documents.completeSemantic(work.document().getId(), response);
            return result;
        } catch (ProviderException exception) {
            if (exception.getKind() == ProviderException.Kind.AMBIGUOUS_SUBMISSION) documents.markSemanticUnknown(work.document().getId());
            else documents.markSemanticFallback(work.document().getId());
            log.warn("Document organization used deterministic fallback: documentId={}, code={}, kind={}",
                    work.document().getId(), exception.getCode(), exception.getKind());
            return DocumentOrganizationService.fallbackFor(work);
        } catch (RuntimeException exception) {
            documents.markSemanticFallback(work.document().getId());
            log.warn("Document organization used deterministic fallback after local processing error: documentId={}, exceptionType={}",
                    work.document().getId(), exception.getClass().getSimpleName());
            return DocumentOrganizationService.fallbackFor(work);
        }
    }
}
