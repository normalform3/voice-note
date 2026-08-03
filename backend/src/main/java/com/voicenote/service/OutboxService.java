package com.voicenote.service;

import com.voicenote.domain.EventType;
import com.voicenote.domain.OutboxEvent;
import com.voicenote.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;

@Service
public class OutboxService {
    private final OutboxEventRepository events;
    public OutboxService(OutboxEventRepository events) { this.events = events; }
    public OutboxEvent enqueue(String aggregateType, String aggregateId, EventType eventType) {
        return events.save(new OutboxEvent(aggregateType, aggregateId, eventType, "{\"aggregateId\":\"" + aggregateId + "\"}"));
    }
}
