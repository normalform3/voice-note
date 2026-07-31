package com.echotrace.messaging;

import com.echotrace.domain.EventType;
import com.echotrace.domain.OutboxEvent;
import com.echotrace.repository.OutboxEventRepository;
import com.echotrace.service.AnalysisService;
import com.echotrace.service.TranscriptionTaskService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskMessageHandler {
    private final JdbcTemplate jdbc;
    private final OutboxEventRepository outbox;
    private final TranscriptionTaskService transcriptionTasks;
    private final AnalysisService analyses;
    public TaskMessageHandler(JdbcTemplate jdbc, OutboxEventRepository outbox, TranscriptionTaskService transcriptionTasks, AnalysisService analyses) {
        this.jdbc = jdbc; this.outbox = outbox; this.transcriptionTasks = transcriptionTasks; this.analyses = analyses;
    }
    @Transactional
    public void consume(String consumerName, String eventId) {
        int inserted = jdbc.update("INSERT IGNORE INTO inbox_messages (id, consumer_name, message_id, received_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP(6))", java.util.UUID.randomUUID().toString(), consumerName, eventId);
        if (inserted == 0) return;
        OutboxEvent event = outbox.findById(eventId).orElseThrow();
        if (event.getEventType() == EventType.TRANSCRIPTION_REQUESTED) transcriptionTasks.ensureFirstAttempt(event.getAggregateId());
        if (event.getEventType() == EventType.ANALYSIS_REQUESTED) analyses.markQueued(event.getAggregateId());
    }
}
