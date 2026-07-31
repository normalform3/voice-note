package com.echotrace.service;

import com.echotrace.domain.EventType;
import com.echotrace.domain.OutboxEvent;
import com.echotrace.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;

@Service
public class OutboxService {
    private final OutboxEventRepository events;
    public OutboxService(OutboxEventRepository events) { this.events = events; }
    public OutboxEvent enqueue(String aggregateType, String aggregateId, EventType eventType) {
        return events.save(new OutboxEvent(aggregateType, aggregateId, eventType, "{\"aggregateId\":\"" + aggregateId + "\"}"));
    }
}
