package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "speaker_correction_runs")
public class SpeakerCorrectionRun {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Version private long version;
    @Column(name = "owner_id", nullable = false, columnDefinition = "CHAR(36)") private String ownerId;
    @Column(name = "transcription_task_id", nullable = false, columnDefinition = "CHAR(36)") private String transcriptionTaskId;
    @Column(name = "transcript_version", nullable = false) private int transcriptVersion;
    @Column(name = "base_revision", nullable = false) private int baseRevision;
    @Column(name = "snapshot_hash", nullable = false, columnDefinition = "CHAR(64)") private String snapshotHash;
    @Column(name = "template_version", nullable = false, length = 64) private String templateVersion;
    @Column(name = "model_id", nullable = false, length = 128) private String modelId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private SpeakerCorrectionRunStatus status;
    @Column(name = "suggestion_count", nullable = false) private int suggestionCount;
    @Column(name = "rejected_count", nullable = false) private int rejectedCount;
    @Column(name = "failure_code", length = 128) private String failureCode;
    @Column(name = "failure_message", length = 1000) private String failureMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "completed_at") private Instant completedAt;

    protected SpeakerCorrectionRun() { }
    public SpeakerCorrectionRun(String ownerId, String taskId, int transcriptVersion, int baseRevision, String snapshotHash,
                                String templateVersion, String modelId) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.transcriptionTaskId = taskId;
        this.transcriptVersion = transcriptVersion; this.baseRevision = baseRevision; this.snapshotHash = snapshotHash;
        this.templateVersion = templateVersion; this.modelId = modelId; this.status = SpeakerCorrectionRunStatus.QUEUED;
        this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getTranscriptionTaskId() { return transcriptionTaskId; }
    public int getTranscriptVersion() { return transcriptVersion; }
    public int getBaseRevision() { return baseRevision; }
    public String getSnapshotHash() { return snapshotHash; }
    public String getTemplateVersion() { return templateVersion; }
    public String getModelId() { return modelId; }
    public SpeakerCorrectionRunStatus getStatus() { return status; }
    public int getSuggestionCount() { return suggestionCount; }
    public int getRejectedCount() { return rejectedCount; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public boolean start() {
        if (status != SpeakerCorrectionRunStatus.QUEUED) return false;
        status = SpeakerCorrectionRunStatus.RUNNING; updatedAt = Instant.now(); return true;
    }
    public void ready(int suggestionCount, int rejectedCount) {
        this.status = SpeakerCorrectionRunStatus.READY; this.suggestionCount = suggestionCount; this.rejectedCount = rejectedCount;
        this.updatedAt = Instant.now(); this.completedAt = updatedAt;
    }
    public void applied() { status = SpeakerCorrectionRunStatus.APPLIED; updatedAt = Instant.now(); completedAt = updatedAt; }
    public void stale() {
        if (status == SpeakerCorrectionRunStatus.APPLIED || status == SpeakerCorrectionRunStatus.FAILED) return;
        status = SpeakerCorrectionRunStatus.STALE; updatedAt = Instant.now(); completedAt = updatedAt;
    }
    public void fail(String code, String message) {
        status = SpeakerCorrectionRunStatus.FAILED; failureCode = code; failureMessage = message;
        updatedAt = Instant.now(); completedAt = updatedAt;
    }
}
