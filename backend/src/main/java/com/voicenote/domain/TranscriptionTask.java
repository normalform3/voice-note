package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transcription_tasks", uniqueConstraints = @UniqueConstraint(name = "uk_transcription_task_semantic", columnNames = {"owner_id", "audio_blob_id", "asr_config_hash", "pipeline_version"}))
public class TranscriptionTask {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Version private long version;
    @Column(name = "owner_id", nullable = false, columnDefinition = "CHAR(36)") private String ownerId;
    @Column(name = "audio_blob_id", nullable = false, columnDefinition = "CHAR(36)") private String audioBlobId;
    @Column(name = "asr_config_hash", nullable = false, columnDefinition = "CHAR(64)") private String asrConfigHash;
    @Column(name = "pipeline_version", nullable = false) private String pipelineVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private TaskStatus status;
    @Column(name = "current_attempt_number", nullable = false) private int currentAttemptNumber;
    @Column(name = "transcript_version", nullable = false) private int transcriptVersion;
    @Column(name = "failure_code") private String failureCode;
    @Column(name = "failure_message") private String failureMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected TranscriptionTask() { }
    public TranscriptionTask(String ownerId, String audioBlobId, String asrConfigHash, String pipelineVersion) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.audioBlobId = audioBlobId;
        this.asrConfigHash = asrConfigHash; this.pipelineVersion = pipelineVersion; this.status = TaskStatus.QUEUED;
        this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getAudioBlobId() { return audioBlobId; }
    public String getAsrConfigHash() { return asrConfigHash; }
    public TaskStatus getStatus() { return status; }
    public int getCurrentAttemptNumber() { return currentAttemptNumber; }
    public int getTranscriptVersion() { return transcriptVersion; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public int nextAttemptNumber() { currentAttemptNumber += 1; updatedAt = Instant.now(); return currentAttemptNumber; }
    public void mark(TaskStatus status) { this.status = status; this.updatedAt = Instant.now(); }
    public void fail(TaskStatus status, String code, String message) { this.status = status; this.failureCode = code; this.failureMessage = message; this.updatedAt = Instant.now(); }
    public int nextTranscriptVersion() { transcriptVersion += 1; updatedAt = Instant.now(); return transcriptVersion; }
}
