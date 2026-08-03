package com.voicenote.messaging;

import com.voicenote.domain.EventType;
import com.voicenote.domain.OutboxEvent;
import com.voicenote.repository.OutboxEventRepository;
import com.voicenote.service.AnalysisService;
import com.voicenote.service.KnowledgeAgentService;
import com.voicenote.service.KnowledgeDocumentService;
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
    private final KnowledgeAgentService knowledgeRuns;
    public TaskMessageHandler(JdbcTemplate jdbc, OutboxEventRepository outbox, TranscriptionTaskService transcriptionTasks, AnalysisService analyses, KnowledgeDocumentService knowledgeDocuments, KnowledgeAgentService knowledgeRuns) {
        this.jdbc = jdbc; this.outbox = outbox; this.transcriptionTasks = transcriptionTasks; this.analyses = analyses; this.knowledgeDocuments = knowledgeDocuments; this.knowledgeRuns = knowledgeRuns;
    }
    @Transactional
    public void consume(String consumerName, String eventId) {
        int inserted = jdbc.update("INSERT IGNORE INTO inbox_messages (id, consumer_name, message_id, received_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP(6))", java.util.UUID.randomUUID().toString(), consumerName, eventId);
        if (inserted == 0) return;
        OutboxEvent event = outbox.findById(eventId).orElseThrow();
        if (event.getEventType() == EventType.TRANSCRIPTION_REQUESTED) transcriptionTasks.ensureFirstAttempt(event.getAggregateId());
        if (event.getEventType() == EventType.ANALYSIS_REQUESTED) analyses.markQueued(event.getAggregateId());
        if (event.getEventType() == EventType.KNOWLEDGE_INDEX_REQUESTED) knowledgeDocuments.markQueued(event.getAggregateId());
        if (event.getEventType() == EventType.KNOWLEDGE_RUN_REQUESTED) knowledgeRuns.markQueued(event.getAggregateId());
    }
}
