package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_runs")
public class KnowledgeRun {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Version private long version;
    @Column(name = "owner_id", nullable = false, columnDefinition = "CHAR(36)") private String ownerId;
    @Column(nullable = false, columnDefinition = "TEXT") private String question;
    @Column(name = "model_id", nullable = false) private String modelId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private KnowledgeRunStatus status;
    @Column(name = "max_tool_calls", nullable = false) private int maxToolCalls;
    @Column(name = "tool_calls_used", nullable = false) private int toolCallsUsed;
    @Column(name = "result_document", columnDefinition = "json") private String resultDocument;
    @Column(name = "failure_message") private String failureMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected KnowledgeRun() { }
    public KnowledgeRun(String ownerId, String question, String modelId, int maxToolCalls) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.question = question; this.modelId = modelId;
        this.maxToolCalls = maxToolCalls; this.status = KnowledgeRunStatus.PENDING; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getQuestion() { return question; }
    public String getModelId() { return modelId; }
    public KnowledgeRunStatus getStatus() { return status; }
    public int getMaxToolCalls() { return maxToolCalls; }
    public int getToolCallsUsed() { return toolCallsUsed; }
    public String getResultDocument() { return resultDocument; }
    public String getFailureMessage() { return failureMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void queue() { if (status == KnowledgeRunStatus.PENDING) { status = KnowledgeRunStatus.QUEUED; updatedAt = Instant.now(); } }
    public boolean start() { if (status != KnowledgeRunStatus.QUEUED) return false; status = KnowledgeRunStatus.RUNNING; updatedAt = Instant.now(); return true; }
    public boolean consumeTool() { if (toolCallsUsed >= maxToolCalls) { status = KnowledgeRunStatus.BUDGET_EXHAUSTED; updatedAt = Instant.now(); return false; } toolCallsUsed++; updatedAt = Instant.now(); return true; }
    public void succeed(String result) { status = KnowledgeRunStatus.SUCCEEDED; resultDocument = result; failureMessage = null; updatedAt = Instant.now(); }
    public void fail(String message) { status = KnowledgeRunStatus.FAILED; failureMessage = message; updatedAt = Instant.now(); }
}
