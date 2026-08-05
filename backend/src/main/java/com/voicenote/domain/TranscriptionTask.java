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
    @Column(name = "asr_config", columnDefinition = "json") private String asrConfig;
    @Column(name = "pipeline_version", nullable = false) private String pipelineVersion;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private TaskStatus status;
    @Enumerated(EnumType.STRING) @Column(name = "current_stage") private PipelineStage currentStage;
    @Enumerated(EnumType.STRING) @Column(name = "current_phase") private PipelinePhase currentPhase;
    @Column(name = "progress_percent", nullable = false) private int progressPercent;
    @Column(name = "transcript_ready", nullable = false) private boolean transcriptReady;
    @Column(name = "current_attempt_number", nullable = false) private int currentAttemptNumber;
    @Column(name = "transcript_version", nullable = false) private int transcriptVersion;
    @Column(name = "failure_code") private String failureCode;
    @Column(name = "failure_message") private String failureMessage;
    @Enumerated(EnumType.STRING) @Column(name = "failed_stage") private PipelineStage failedStage;
    @Column(name = "cancel_requested_at") private Instant cancelRequestedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected TranscriptionTask() { }
    public TranscriptionTask(String ownerId, String audioBlobId, String asrConfigHash, String pipelineVersion) {
        this(ownerId, audioBlobId, asrConfigHash, null, pipelineVersion);
    }
    public TranscriptionTask(String ownerId, String audioBlobId, String asrConfigHash, String asrConfig, String pipelineVersion) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.audioBlobId = audioBlobId;
        this.asrConfigHash = asrConfigHash; this.asrConfig = asrConfig; this.pipelineVersion = pipelineVersion; this.status = TaskStatus.QUEUED;
        this.currentStage = PipelineStage.ASR_SUBMIT; this.currentPhase = PipelinePhase.TRANSCRIPTION;
        this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getAudioBlobId() { return audioBlobId; }
    public String getAsrConfigHash() { return asrConfigHash; }
    public String getAsrConfig() { return asrConfig; }
    public TaskStatus getStatus() { return status; }
    public PipelineStage getCurrentStage() { return currentStage; }
    public PipelinePhase getCurrentPhase() { return currentPhase; }
    public int getProgressPercent() { return progressPercent; }
    public boolean isTranscriptReady() { return transcriptReady; }
    public int getCurrentAttemptNumber() { return currentAttemptNumber; }
    public int getTranscriptVersion() { return transcriptVersion; }
    public String getFailureCode() { return failureCode; }
    public String getFailureMessage() { return failureMessage; }
    public PipelineStage getFailedStage() { return failedStage; }
    public boolean isCancelled() { return status == TaskStatus.CANCELLED; }
    public int nextAttemptNumber() { currentAttemptNumber += 1; updatedAt = Instant.now(); return currentAttemptNumber; }
    public void mark(TaskStatus status) { this.status = status; this.updatedAt = Instant.now(); }
    public void advance(PipelineStage stage, int progress) { this.currentStage = stage; this.currentPhase = phaseFor(stage); this.progressPercent = progress; this.failureCode = null; this.failureMessage = null; this.failedStage = null; this.updatedAt = Instant.now(); }
    public void transcriptPersisted() { this.transcriptReady = true; this.updatedAt = Instant.now(); }
    public void awaitFormalDocument() {
        this.status = TaskStatus.WAITING_FOR_FORMAL_DOCUMENT; this.currentStage = PipelineStage.RAW_DOCUMENT_READY;
        this.currentPhase = PipelinePhase.RAW_DOCUMENT_REVIEW; this.progressPercent = 60;
        this.failureCode = null; this.failureMessage = null; this.failedStage = null; this.updatedAt = Instant.now();
    }
    public void awaitKnowledgeBuild() {
        this.status = TaskStatus.WAITING_FOR_KNOWLEDGE_BUILD; this.currentStage = PipelineStage.FORMAL_DOCUMENT_READY;
        this.currentPhase = PipelinePhase.FORMAL_DOCUMENT_REVIEW; this.progressPercent = 80;
        this.failureCode = null; this.failureMessage = null; this.failedStage = null; this.updatedAt = Instant.now();
    }
    public void completePipeline() { this.status = TaskStatus.SUCCEEDED; this.currentStage = PipelineStage.COMPLETED; this.currentPhase = PipelinePhase.COMPLETED; this.progressPercent = 100; this.failedStage = null; this.failureCode = null; this.failureMessage = null; this.updatedAt = Instant.now(); }
    public void fail(TaskStatus status, String code, String message) { this.status = status; this.failureCode = code; this.failureMessage = message; this.failedStage = currentStage; this.updatedAt = Instant.now(); }
    public int nextTranscriptVersion() { transcriptVersion += 1; updatedAt = Instant.now(); return transcriptVersion; }
    public boolean cancel() {
        if (status == TaskStatus.SUCCEEDED || status == TaskStatus.CANCELLED) return false;
        status = TaskStatus.CANCELLED; cancelRequestedAt = Instant.now(); updatedAt = cancelRequestedAt; return true;
    }
    private static PipelinePhase phaseFor(PipelineStage stage) {
        return switch (stage) {
            case UPLOAD_COMPLETED, ASR_SUBMIT, ASR_POLL, TRANSCRIPT_PERSIST -> PipelinePhase.TRANSCRIPTION;
            case RAW_DOCUMENT_READY -> PipelinePhase.RAW_DOCUMENT_REVIEW;
            case DOCUMENT_ORGANIZATION -> PipelinePhase.DOCUMENT_ORGANIZATION;
            case FORMAL_DOCUMENT_READY -> PipelinePhase.FORMAL_DOCUMENT_REVIEW;
            case KNOWLEDGE_PREPARE, KNOWLEDGE_INDEX -> PipelinePhase.KNOWLEDGE_BUILD;
            case COMPLETED -> PipelinePhase.COMPLETED;
        };
    }
}
