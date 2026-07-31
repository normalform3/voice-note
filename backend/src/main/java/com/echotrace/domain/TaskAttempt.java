package com.echotrace.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task_attempts", uniqueConstraints = @UniqueConstraint(name = "uk_task_attempt_number", columnNames = {"transcription_task_id", "attempt_number"}))
public class TaskAttempt {
    @Id private String id;
    @Version private long version;
    @Column(name = "transcription_task_id", nullable = false) private String transcriptionTaskId;
    @Column(name = "attempt_number", nullable = false) private int attemptNumber;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AttemptStatus status;
    @Column(name = "provider_task_id") private String providerTaskId;
    @Column(name = "provider_input_url") private String providerInputUrl;
    @Column(name = "next_poll_at") private Instant nextPollAt;
    @Column(name = "error_code") private String errorCode;
    @Column(name = "error_message") private String errorMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected TaskAttempt() { }
    public TaskAttempt(String transcriptionTaskId, int attemptNumber) {
        this.id = UUID.randomUUID().toString(); this.transcriptionTaskId = transcriptionTaskId; this.attemptNumber = attemptNumber;
        this.status = AttemptStatus.QUEUED; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public String getTranscriptionTaskId() { return transcriptionTaskId; }
    public int getAttemptNumber() { return attemptNumber; }
    public AttemptStatus getStatus() { return status; }
    public String getProviderTaskId() { return providerTaskId; }
    public Instant getNextPollAt() { return nextPollAt; }
    public boolean claimSubmission() { if (status != AttemptStatus.QUEUED) return false; status = AttemptStatus.SUBMITTING; updatedAt = Instant.now(); return true; }
    public void submitted(String providerTaskId, String providerInputUrl) { this.providerTaskId = providerTaskId; this.providerInputUrl = providerInputUrl; this.status = AttemptStatus.PROVIDER_RUNNING; this.nextPollAt = Instant.now().plusSeconds(5); this.updatedAt = Instant.now(); }
    public void reschedulePoll() { this.nextPollAt = Instant.now().plusSeconds(8); this.updatedAt = Instant.now(); }
    public void succeed() { this.status = AttemptStatus.SUCCEEDED; this.nextPollAt = null; this.updatedAt = Instant.now(); }
    public void fail(AttemptStatus status, String code, String message) { this.status = status; this.errorCode = code; this.errorMessage = message; this.updatedAt = Instant.now(); }
}
