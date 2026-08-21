package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.AgentMetrics;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.EventType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AgentLiveEventService {
    private final AppProperties properties;
    private final OutboxService outbox;
    private final ProgressEventPublisher progress;
    private final ObjectMapper mapper;
    private final AgentMetrics metrics;

    public AgentLiveEventService(AppProperties properties, OutboxService outbox, ProgressEventPublisher progress,
                                 ObjectMapper mapper, AgentMetrics metrics) {
        this.properties = properties; this.outbox = outbox; this.progress = progress; this.mapper = mapper; this.metrics = metrics;
    }

    public void progress(String ownerId, String runId, long sequence, String phase, String message,
                         boolean speakable, Instant runCreatedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", runId); data.put("sequence", sequence); data.put("phase", phase);
        data.put("message", message); data.put("speakable", speakable); data.put("occurredAt", Instant.now().toString());
        publish(ownerId, runId, "agent-run-progress", sequence, data);
        if ("ACCEPTED".equals(phase)) metrics.liveLatency("first_progress", between(runCreatedAt));
    }

    public void answerBlock(String ownerId, String runId, long sequence, int blockIndex, JsonNode block,
                            String spokenText, Instant runCreatedAt) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runId", runId); data.put("sequence", sequence); data.put("blockIndex", blockIndex);
        data.put("block", block); data.put("spokenText", spokenText == null ? "" : spokenText);
        data.put("occurredAt", Instant.now().toString());
        publish(ownerId, runId, "agent-answer-block", sequence, data);
        if (blockIndex == 0) metrics.liveLatency("first_answer_block", between(runCreatedAt));
    }

    private void publish(String ownerId, String runId, String eventName, long sequence, Map<String, Object> data) {
        if (properties.getRocketmq().isEnabled()) {
            try {
                String payload = mapper.writeValueAsString(Map.of("eventName", eventName, "data", data));
                outbox.enqueue("agent_live_event", runId, EventType.PROGRESS_CHANGED, payload,
                        "agent-live:" + runId + ":" + sequence);
            } catch (Exception ignored) {
                // Live feedback is best effort and must never fail the durable Agent Run.
            }
        } else {
            progress.publish(new ProgressEventPublisher.ProgressNotification(ownerId, eventName, runId, data));
        }
    }

    private static Duration between(Instant started) {
        return started == null ? Duration.ZERO : Duration.between(started, Instant.now()).isNegative()
                ? Duration.ZERO : Duration.between(started, Instant.now());
    }
}
