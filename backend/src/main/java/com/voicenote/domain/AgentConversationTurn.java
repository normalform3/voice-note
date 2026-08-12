package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_conversation_turns", uniqueConstraints = @UniqueConstraint(
        name = "uk_agent_conversation_turn", columnNames = {"conversation_id", "turn_index"}))
public class AgentConversationTurn {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "conversation_id", nullable = false, columnDefinition = "CHAR(36)") private String conversationId;
    @Column(name = "owner_id", nullable = false, columnDefinition = "CHAR(36)") private String ownerId;
    @Column(name = "turn_index", nullable = false) private int turnIndex;
    @Column(name = "user_message", nullable = false, columnDefinition = "TEXT") private String userMessage;
    @Column(name = "knowledge_run_id", columnDefinition = "CHAR(36)") private String knowledgeRunId;
    @Enumerated(EnumType.STRING) @Column(name = "extraction_status", nullable = false) private MemoryExtractionStatus extractionStatus;
    @Column(name = "extraction_attempts", nullable = false) private int extractionAttempts;
    @Column(name = "extraction_input_hash", columnDefinition = "CHAR(64)") private String extractionInputHash;
    @Column(name = "extraction_prompt_version", length = 64) private String extractionPromptVersion;
    @Column(name = "extraction_model_id", length = 128) private String extractionModelId;
    @Column(name = "extraction_duration_ms") private Long extractionDurationMs;
    @Column(name = "extraction_failure_code", length = 128) private String extractionFailureCode;
    @Column(name = "extraction_failure_message") private String extractionFailureMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected AgentConversationTurn() { }
    public AgentConversationTurn(String conversationId, String ownerId, int turnIndex, String userMessage) {
        this.id = UUID.randomUUID().toString(); this.conversationId = conversationId; this.ownerId = ownerId;
        this.turnIndex = turnIndex; this.userMessage = userMessage; this.extractionStatus = MemoryExtractionStatus.NOT_REQUESTED;
        this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public String getConversationId() { return conversationId; }
    public String getOwnerId() { return ownerId; }
    public int getTurnIndex() { return turnIndex; }
    public String getUserMessage() { return userMessage; }
    public String getKnowledgeRunId() { return knowledgeRunId; }
    public void detachRun() { knowledgeRunId = null; updatedAt = Instant.now(); }
    public MemoryExtractionStatus getExtractionStatus() { return extractionStatus; }
    public int getExtractionAttempts() { return extractionAttempts; }
    public String getExtractionFailureMessage() { return extractionFailureMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void attachRun(String runId) { knowledgeRunId = runId; updatedAt = Instant.now(); }
    public void queueExtraction(String inputHash) { queueExtraction(inputHash, null); }
    public void queueExtraction(String inputHash, String promptVersion) {
        extractionStatus = MemoryExtractionStatus.QUEUED; extractionInputHash = inputHash;
        extractionPromptVersion = promptVersion;
        extractionFailureMessage = null; updatedAt = Instant.now();
    }
    public void skipExtraction() { extractionStatus = MemoryExtractionStatus.SKIPPED; updatedAt = Instant.now(); }
    public boolean beginExtraction() {
        if (extractionStatus != MemoryExtractionStatus.QUEUED) return false;
        extractionStatus = MemoryExtractionStatus.RUNNING; extractionAttempts++; updatedAt = Instant.now(); return true;
    }
    public void completeExtraction() { completeExtraction(null, null); }
    public void completeExtraction(String modelId, Long durationMs) {
        extractionStatus = MemoryExtractionStatus.SUCCEEDED; extractionModelId = modelId; extractionDurationMs = durationMs;
        extractionFailureCode = null; extractionFailureMessage = null; updatedAt = Instant.now();
    }
    public void failExtraction(String message, boolean retry) {
        failExtraction(null, message, null, null, retry);
    }
    public void failExtraction(String code, String message, String modelId, Long durationMs, boolean retry) {
        extractionStatus = retry ? MemoryExtractionStatus.QUEUED : MemoryExtractionStatus.FAILED;
        extractionFailureCode = code; extractionFailureMessage = message; extractionModelId = modelId;
        extractionDurationMs = durationMs; updatedAt = Instant.now();
    }
    public void retryExtraction(String inputHash) {
        extractionStatus = MemoryExtractionStatus.QUEUED; extractionInputHash = inputHash;
        extractionFailureMessage = null; updatedAt = Instant.now();
    }
}
