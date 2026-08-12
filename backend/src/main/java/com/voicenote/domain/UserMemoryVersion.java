package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_memory_versions", uniqueConstraints = @UniqueConstraint(
        name = "uk_user_memory_version_number", columnNames = {"memory_id", "version_number"}))
public class UserMemoryVersion {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "memory_id", nullable = false, columnDefinition = "CHAR(36)") private String memoryId;
    @Column(name = "version_number", nullable = false) private int versionNumber;
    @Column(nullable = false, columnDefinition = "TEXT") private String content;
    @Column(name = "source_candidate_id", columnDefinition = "CHAR(36)") private String sourceCandidateId;
    @Enumerated(EnumType.STRING) @Column(name = "index_status", nullable = false) private MemoryIndexStatus indexStatus;
    @Column(name = "index_attempts", nullable = false) private int indexAttempts;
    @Column(name = "index_failure_message") private String indexFailureMessage;
    @Column(name = "confirmed_at", nullable = false) private Instant confirmedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected UserMemoryVersion() { }
    public UserMemoryVersion(String memoryId, int versionNumber, String content, String sourceCandidateId) {
        this.id = UUID.randomUUID().toString(); this.memoryId = memoryId; this.versionNumber = versionNumber;
        this.content = content; this.sourceCandidateId = sourceCandidateId; this.indexStatus = MemoryIndexStatus.QUEUED;
        this.confirmedAt = Instant.now(); this.createdAt = confirmedAt;
    }
    public String getId() { return id; }
    public String getMemoryId() { return memoryId; }
    public int getVersionNumber() { return versionNumber; }
    public String getContent() { return content; }
    public String getSourceCandidateId() { return sourceCandidateId; }
    public MemoryIndexStatus getIndexStatus() { return indexStatus; }
    public int getIndexAttempts() { return indexAttempts; }
    public String getIndexFailureMessage() { return indexFailureMessage; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public boolean beginIndexing() {
        if (indexStatus != MemoryIndexStatus.QUEUED) return false;
        indexStatus = MemoryIndexStatus.RUNNING; indexAttempts++; return true;
    }
    public void indexed() { indexStatus = MemoryIndexStatus.READY; indexFailureMessage = null; }
    public void failIndex(String message, boolean retry) { indexStatus = retry ? MemoryIndexStatus.QUEUED : MemoryIndexStatus.FAILED; indexFailureMessage = message; }
    public void retryIndex() { indexStatus = MemoryIndexStatus.QUEUED; indexFailureMessage = null; }
}
