package com.echotrace.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inbox_messages", uniqueConstraints = @UniqueConstraint(name = "uk_inbox_consumer_message", columnNames = {"consumer_name", "message_id"}))
public class InboxMessage {
    @Id private String id;
    @Column(name = "consumer_name", nullable = false) private String consumerName;
    @Column(name = "message_id", nullable = false) private String messageId;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    protected InboxMessage() { }
    public InboxMessage(String consumerName, String messageId) { this.id = UUID.randomUUID().toString(); this.consumerName = consumerName; this.messageId = messageId; this.receivedAt = Instant.now(); }
}
