package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task_stage_attempts", uniqueConstraints = @UniqueConstraint(name = "uk_task_stage_attempt", columnNames = {"transcription_task_id", "stage", "attempt_number"}))
public class TaskStageAttempt {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Version private long version;
    @Column(name = "transcription_task_id", nullable = false, columnDefinition = "CHAR(36)") private String transcriptionTaskId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private PipelineStage stage;
    @Column(name = "attempt_number", nullable = false) private int attemptNumber;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private StageAttemptStatus status;
    @Column(name = "queued_at", nullable = false) private Instant queuedAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "wait_duration_ms") private Long waitDurationMs;
    @Column(name = "next_retry_at") private Instant nextRetryAt;
    @Column(name = "lease_until") private Instant leaseUntil;
    @Column(name = "error_code") private String errorCode;
    @Column(name = "error_message") private String errorMessage;
    @Column(name = "result_snapshot", columnDefinition = "json") private String resultSnapshot;

    protected TaskStageAttempt() { }
    public TaskStageAttempt(String taskId, PipelineStage stage, int attemptNumber) {
        this.id = UUID.randomUUID().toString(); this.transcriptionTaskId = taskId; this.stage = stage; this.attemptNumber = attemptNumber;
        this.status = StageAttemptStatus.QUEUED; this.queuedAt = Instant.now();
    }

    public String getId() { return id; }
    public String getTranscriptionTaskId() { return transcriptionTaskId; }
    public PipelineStage getStage() { return stage; }
    public int getAttemptNumber() { return attemptNumber; }
    public StageAttemptStatus getStatus() { return status; }
    public Instant getQueuedAt() { return queuedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Long getWaitDurationMs() { return waitDurationMs; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public String getResultSnapshot() { return resultSnapshot; }
    public boolean start() {
        if (status != StageAttemptStatus.QUEUED && status != StageAttemptStatus.RETRY_WAIT) return false;
        Instant now = Instant.now(); status = StageAttemptStatus.RUNNING; startedAt = now; leaseUntil = now.plusSeconds(90);
        waitDurationMs = Duration.between(queuedAt, now).toMillis(); nextRetryAt = null; return true;
    }
    public void succeed(String snapshot) { status = StageAttemptStatus.SUCCEEDED; completedAt = Instant.now(); leaseUntil = null; resultSnapshot = snapshot; errorCode = null; errorMessage = null; }
    public void retry(String code, String message, Instant nextRetry) { status = StageAttemptStatus.RETRY_WAIT; errorCode = code; errorMessage = message; nextRetryAt = nextRetry; leaseUntil = null; completedAt = Instant.now(); }
    public void retried() { status = StageAttemptStatus.RETRIED; nextRetryAt = null; leaseUntil = null; }
    public void fail(String code, String message, boolean unknown) { status = unknown ? StageAttemptStatus.UNKNOWN : StageAttemptStatus.FAILED; errorCode = code; errorMessage = message; completedAt = Instant.now(); leaseUntil = null; }
}
