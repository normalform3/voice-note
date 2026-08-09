package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_run_steps", uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_run_step_index", columnNames = {"knowledge_run_id", "step_index"}))
public class KnowledgeRunStep {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "knowledge_run_id", nullable = false, columnDefinition = "CHAR(36)") private String knowledgeRunId;
    @Column(name = "step_index", nullable = false) private int stepIndex;
    @Enumerated(EnumType.STRING) @Column(name = "step_type", nullable = false) private AgentStepType stepType;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AgentStepStatus status;
    @Column(name = "tool_call_id") private String toolCallId;
    @Column(name = "tool_name") private String toolName;
    @Column(name = "input_document", columnDefinition = "json") private String inputDocument;
    @Column(name = "output_document", columnDefinition = "json") private String outputDocument;
    @Column(name = "summary_text", length = 1000) private String summaryText;
    @Column(name = "error_code") private String errorCode;
    @Column(name = "error_message", length = 1000) private String errorMessage;
    @Column(name = "duration_ms") private Long durationMs;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "completed_at") private Instant completedAt;

    protected KnowledgeRunStep() { }

    public KnowledgeRunStep(String runId, int index, AgentStepType type, String toolCallId, String toolName, String input) {
        this.id = UUID.randomUUID().toString(); this.knowledgeRunId = runId; this.stepIndex = index; this.stepType = type;
        this.toolCallId = toolCallId; this.toolName = toolName; this.inputDocument = input;
        this.status = AgentStepStatus.RUNNING; this.createdAt = Instant.now();
    }

    public void succeed(String output, String summary, long durationMs) {
        this.status = AgentStepStatus.SUCCEEDED; this.outputDocument = output; this.summaryText = summary;
        this.durationMs = durationMs; this.completedAt = Instant.now();
    }

    public void fail(String code, String message, long durationMs) {
        this.status = AgentStepStatus.FAILED; this.errorCode = code; this.errorMessage = message;
        this.durationMs = durationMs; this.completedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getToolCallId() { return toolCallId; }
    public String getInputDocument() { return inputDocument; }
    public String getOutputDocument() { return outputDocument; }
    public int getStepIndex() { return stepIndex; }
    public AgentStepType getStepType() { return stepType; }
    public AgentStepStatus getStatus() { return status; }
    public String getToolName() { return toolName; }
    public String getSummaryText() { return summaryText; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public Long getDurationMs() { return durationMs; }
    public Instant getCreatedAt() { return createdAt; }
}
