package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_conversation_documents", uniqueConstraints = @UniqueConstraint(
        name = "uk_agent_conversation_document", columnNames = {"conversation_id", "transcription_task_id"}))
public class AgentConversationDocument {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "conversation_id", nullable = false, columnDefinition = "CHAR(36)") private String conversationId;
    @Column(name = "transcription_task_id", nullable = false, columnDefinition = "CHAR(36)") private String transcriptionTaskId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected AgentConversationDocument() { }
    public AgentConversationDocument(String conversationId, String taskId) {
        this.id = UUID.randomUUID().toString(); this.conversationId = conversationId;
        this.transcriptionTaskId = taskId; this.createdAt = Instant.now();
    }
    public String getConversationId() { return conversationId; }
    public String getTranscriptionTaskId() { return transcriptionTaskId; }
}
