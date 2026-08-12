package com.voicenote.service;

import com.voicenote.domain.EventType;
import com.voicenote.domain.OutboxEvent;
import com.voicenote.domain.OutboxStatus;
import com.voicenote.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Service
public class OutboxService {
    private final OutboxEventRepository events;
    public OutboxService(OutboxEventRepository events) { this.events = events; }
    @Transactional
    public OutboxEvent enqueue(String aggregateType, String aggregateId, EventType eventType) {
        return enqueue(aggregateType, aggregateId, eventType, "{\"aggregateId\":\"" + aggregateId + "\"}", null);
    }
    @Transactional
    public OutboxEvent enqueue(String aggregateType, String aggregateId, EventType eventType, String payload, String deduplicationKey) {
        if (deduplicationKey != null) {
            var existing = events.findByDeduplicationKey(deduplicationKey);
            if (existing.isPresent()) return existing.get();
            Instant now = Instant.now();
            events.insertIgnore(UUID.randomUUID().toString(), aggregateType, aggregateId, eventType.name(), deduplicationKey, payload,
                    OutboxStatus.READY.name(), now, now);
            return events.findByDeduplicationKey(deduplicationKey).orElseThrow();
        }
        return events.save(new OutboxEvent(aggregateType, aggregateId, eventType, payload, null));
    }
    @Transactional public void deleteAggregate(String aggregateType, String aggregateId) {
        events.deleteByAggregateTypeAndAggregateId(aggregateType, aggregateId);
    }
}
