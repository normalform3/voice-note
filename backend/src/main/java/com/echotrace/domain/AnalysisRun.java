package com.echotrace.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "analysis_runs", uniqueConstraints = @UniqueConstraint(name = "uk_analysis_semantic", columnNames = {"owner_id", "transcription_task_id", "semantic_hash"}))
public class AnalysisRun {
    @Id private String id;
    @Version private long version;
    @Column(name = "owner_id", nullable = false) private String ownerId;
    @Column(name = "transcription_task_id", nullable = false) private String transcriptionTaskId;
    @Column(name = "transcript_snapshot_hash", nullable = false) private String transcriptSnapshotHash;
    @Column(name = "analysis_mode", nullable = false) private String analysisMode;
    @Column(name = "custom_goal", nullable = false, columnDefinition = "TEXT") private String customGoal;
    @Column(name = "template_version", nullable = false) private String templateVersion;
    @Column(name = "model_id", nullable = false) private String modelId;
    @Column(name = "semantic_hash", nullable = false) private String semanticHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AnalysisRunStatus status;
    @Column(name = "repair_rounds", nullable = false) private int repairRounds;
    @Column(name = "max_calls", nullable = false) private int maxCalls;
    @Column(name = "calls_used", nullable = false) private int callsUsed;
    @Column(name = "result_document", columnDefinition = "json") private String resultDocument;
    @Column(name = "quality_status") private String qualityStatus;
    @Column(name = "failure_message") private String failureMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected AnalysisRun() { }
    public AnalysisRun(String ownerId, String taskId, String snapshotHash, String mode, String goal, String templateVersion, String modelId, String semanticHash, int maxCalls) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.transcriptionTaskId = taskId; this.transcriptSnapshotHash = snapshotHash;
        this.analysisMode = mode; this.customGoal = goal; this.templateVersion = templateVersion; this.modelId = modelId; this.semanticHash = semanticHash;
        this.status = AnalysisRunStatus.QUEUED; this.maxCalls = maxCalls; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getTranscriptionTaskId() { return transcriptionTaskId; }
    public String getTranscriptSnapshotHash() { return transcriptSnapshotHash; }
    public String getAnalysisMode() { return analysisMode; }
    public String getCustomGoal() { return customGoal; }
    public String getModelId() { return modelId; }
    public AnalysisRunStatus getStatus() { return status; }
    public String getResultDocument() { return resultDocument; }
    public int getCallsUsed() { return callsUsed; }
    public int getMaxCalls() { return maxCalls; }
    public boolean start() { if (status != AnalysisRunStatus.QUEUED) return false; status = AnalysisRunStatus.RUNNING; updatedAt = Instant.now(); return true; }
    public boolean consumeCall() { if (callsUsed >= maxCalls) { status = AnalysisRunStatus.BUDGET_EXHAUSTED; updatedAt = Instant.now(); return false; } callsUsed++; updatedAt = Instant.now(); return true; }
    public void succeed(String result, String qualityStatus) { this.status = AnalysisRunStatus.SUCCEEDED; this.resultDocument = result; this.qualityStatus = qualityStatus; this.updatedAt = Instant.now(); }
    public void fail(String message) { this.status = AnalysisRunStatus.FAILED; this.failureMessage = message; this.updatedAt = Instant.now(); }
}
