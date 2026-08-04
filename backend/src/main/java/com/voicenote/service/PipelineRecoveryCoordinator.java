package com.voicenote.service;

import com.voicenote.config.AppProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Restores durable work after a process crash without trusting in-memory scheduling. */
@Component
public class PipelineRecoveryCoordinator {
    private final AppProperties properties;
    private final PipelineProgressService pipeline;
    private final AsrWorker.AsrAttemptState asr;
    private final OrganizedDocumentWorker organizedDocuments;
    private final KnowledgeIndexWorker knowledgeIndex;
    private final DocumentOrganizationService organizationState;
    private final KnowledgeDocumentService knowledgeState;

    public PipelineRecoveryCoordinator(AppProperties properties, PipelineProgressService pipeline, AsrWorker.AsrAttemptState asr,
                                       OrganizedDocumentWorker organizedDocuments, KnowledgeIndexWorker knowledgeIndex,
                                       DocumentOrganizationService organizationState, KnowledgeDocumentService knowledgeState) {
        this.properties = properties; this.pipeline = pipeline; this.asr = asr; this.organizedDocuments = organizedDocuments;
        this.knowledgeIndex = knowledgeIndex; this.organizationState = organizationState; this.knowledgeState = knowledgeState;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAtStartup() { recover(); }

    @Scheduled(fixedDelayString = "${app.workers.recovery-interval-ms:30000}")
    public void recover() {
        if (!properties.getWorkers().isEnabled()) return;
        pipeline.recoverExpiredLeases();
        for (PipelineProgressService.RetryWork retry : pipeline.dueRetries()) {
            if (retry.stage() != com.voicenote.domain.PipelineStage.DOCUMENT_ORGANIZATION && retry.stage() != com.voicenote.domain.PipelineStage.KNOWLEDGE_INDEX) continue;
            if (!pipeline.activateRetry(retry.stageAttemptId())) continue;
            if (retry.stage() == com.voicenote.domain.PipelineStage.DOCUMENT_ORGANIZATION) {
                String documentId = organizationState.recoverForTask(retry.taskId());
                if (documentId != null) organizedDocuments.process(documentId);
            } else {
                String documentId = knowledgeState.recoverForTask(retry.taskId());
                if (documentId != null) knowledgeIndex.process(documentId);
            }
        }
        asr.activateDueRetries();
        organizedDocuments.recoverQueued();
        knowledgeIndex.recoverQueued();
    }
}
