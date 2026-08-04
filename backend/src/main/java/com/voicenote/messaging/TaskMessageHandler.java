package com.voicenote.messaging;

import com.voicenote.domain.EventType;
import com.voicenote.domain.OutboxEvent;
import com.voicenote.repository.OutboxEventRepository;
import com.voicenote.service.AnalysisService;
import com.voicenote.service.KnowledgeAgentService;
import com.voicenote.service.KnowledgeDocumentService;
import com.voicenote.service.DocumentOrganizationService;
import com.voicenote.service.OrganizedDocumentWorker;
import com.voicenote.service.KnowledgeIndexWorker;
import com.voicenote.service.TranscriptionTaskService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskMessageHandler {
    private final JdbcTemplate jdbc;
    private final OutboxEventRepository outbox;
    private final TranscriptionTaskService transcriptionTasks;
    private final AnalysisService analyses;
    private final KnowledgeDocumentService knowledgeDocuments;
    private final DocumentOrganizationService organizedDocuments;
    private final OrganizedDocumentWorker organizedDocumentWorker;
    private final KnowledgeIndexWorker knowledgeIndexWorker;
    private final KnowledgeAgentService knowledgeRuns;
    private final ProgressMessageHandler progress;
    public TaskMessageHandler(JdbcTemplate jdbc, OutboxEventRepository outbox, TranscriptionTaskService transcriptionTasks, AnalysisService analyses,
                              KnowledgeDocumentService knowledgeDocuments, DocumentOrganizationService organizedDocuments,
                              OrganizedDocumentWorker organizedDocumentWorker, KnowledgeIndexWorker knowledgeIndexWorker,
                              KnowledgeAgentService knowledgeRuns, ProgressMessageHandler progress) {
        this.jdbc = jdbc; this.outbox = outbox; this.transcriptionTasks = transcriptionTasks; this.analyses = analyses;
        this.knowledgeDocuments = knowledgeDocuments; this.organizedDocuments = organizedDocuments; this.organizedDocumentWorker = organizedDocumentWorker;
        this.knowledgeIndexWorker = knowledgeIndexWorker; this.knowledgeRuns = knowledgeRuns; this.progress = progress;
    }
    @Transactional
    public void consume(String consumerName, String eventId) {
        int inserted = jdbc.update("INSERT IGNORE INTO inbox_messages (id, consumer_name, message_id, received_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP(6))", java.util.UUID.randomUUID().toString(), consumerName, eventId);
        if (inserted == 0) return;
        OutboxEvent event = outbox.findById(eventId).orElse(null);
        if (event == null) return;
        if (event.getEventType() == EventType.TRANSCRIPTION_REQUESTED) transcriptionTasks.ensureFirstAttempt(event.getAggregateId());
        if (event.getEventType() == EventType.DOCUMENT_ORGANIZATION_REQUESTED) {
            organizedDocuments.markQueued(event.getAggregateId());
            afterCommit(() -> organizedDocumentWorker.process(event.getAggregateId()));
        }
        if (event.getEventType() == EventType.ANALYSIS_REQUESTED) analyses.markQueued(event.getAggregateId());
        if (event.getEventType() == EventType.KNOWLEDGE_INDEX_REQUESTED) {
            knowledgeDocuments.markQueued(event.getAggregateId());
            afterCommit(() -> knowledgeIndexWorker.process(event.getAggregateId()));
        }
        if (event.getEventType() == EventType.KNOWLEDGE_RUN_REQUESTED) knowledgeRuns.markQueued(event.getAggregateId());
        if (event.getEventType() == EventType.PROGRESS_CHANGED && "in-process".equals(consumerName)) progress.consume("in-process-progress", eventId);
    }
    private static void afterCommit(Runnable action) {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
}
