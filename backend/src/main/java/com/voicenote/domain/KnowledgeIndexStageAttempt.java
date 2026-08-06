package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_index_stage_attempts", uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_index_stage_attempt", columnNames = {"knowledge_index_version_id", "stage", "attempt_number"}))
public class KnowledgeIndexStageAttempt {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Version private long version;
    @Column(name = "knowledge_index_version_id", nullable = false, columnDefinition = "CHAR(36)") private String knowledgeIndexVersionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private KnowledgeIndexStage stage;
    @Column(name = "attempt_number", nullable = false) private int attemptNumber;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private StageAttemptStatus status;
    @Column(name = "progress_percent", nullable = false) private int progressPercent;
    @Column(name = "completed_count", nullable = false) private int completedCount;
    @Column(name = "total_count", nullable = false) private int totalCount;
    @Column(name = "queued_at", nullable = false) private Instant queuedAt;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "next_retry_at") private Instant nextRetryAt;
    @Column(name = "error_code") private String errorCode;
    @Column(name = "error_message") private String errorMessage;
    @Column(name = "result_snapshot", columnDefinition = "json") private String resultSnapshot;

    protected KnowledgeIndexStageAttempt() { }
    public KnowledgeIndexStageAttempt(String indexVersionId, KnowledgeIndexStage stage, int attemptNumber) {
        this.id = UUID.randomUUID().toString(); this.knowledgeIndexVersionId = indexVersionId; this.stage = stage; this.attemptNumber = attemptNumber;
        this.status = StageAttemptStatus.QUEUED; this.queuedAt = Instant.now();
    }
    public String getId() { return id; }
    public String getKnowledgeIndexVersionId() { return knowledgeIndexVersionId; }
    public KnowledgeIndexStage getStage() { return stage; }
    public int getAttemptNumber() { return attemptNumber; }
    public StageAttemptStatus getStatus() { return status; }
    public int getProgressPercent() { return progressPercent; }
    public int getCompletedCount() { return completedCount; }
    public int getTotalCount() { return totalCount; }
    public Instant getQueuedAt() { return queuedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public boolean start() { if (status != StageAttemptStatus.QUEUED && status != StageAttemptStatus.RETRY_WAIT) return false; status = StageAttemptStatus.RUNNING; startedAt = Instant.now(); nextRetryAt = null; return true; }
    public void progress(int completed, int total) { completedCount = completed; totalCount = total; progressPercent = total <= 0 ? 100 : Math.min(100, (int) Math.floor(completed * 100.0 / total)); }
    public void succeed(String snapshot) { progressPercent = 100; status = StageAttemptStatus.SUCCEEDED; completedAt = Instant.now(); resultSnapshot = snapshot; errorCode = null; errorMessage = null; }
    public void fail(String code, String message) { status = StageAttemptStatus.FAILED; completedAt = Instant.now(); errorCode = code; errorMessage = message; }
}
