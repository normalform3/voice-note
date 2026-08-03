package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "aggregate_type", nullable = false) private String aggregateType;
    @Column(name = "aggregate_id", nullable = false, columnDefinition = "CHAR(36)") private String aggregateId;
    @Enumerated(EnumType.STRING) @Column(name = "event_type", nullable = false) private EventType eventType;
    @Column(nullable = false, columnDefinition = "json") private String payload;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private OutboxStatus status;
    @Column(nullable = false) private int attempts;
    @Column(name = "available_at", nullable = false) private Instant availableAt;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected OutboxEvent() { }
    public OutboxEvent(String aggregateType, String aggregateId, EventType eventType, String payload) {
        this.id = UUID.randomUUID().toString(); this.aggregateType = aggregateType; this.aggregateId = aggregateId; this.eventType = eventType; this.payload = payload;
        this.status = OutboxStatus.READY; this.availableAt = Instant.now(); this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getAggregateId() { return aggregateId; }
    public EventType getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public OutboxStatus getStatus() { return status; }
    public void markPublished() { status = OutboxStatus.PUBLISHED; publishedAt = Instant.now(); attempts++; }
    public void defer() { attempts++; availableAt = Instant.now().plusSeconds(Math.min(60, attempts * 5L)); }
}
