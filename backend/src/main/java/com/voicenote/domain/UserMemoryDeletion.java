package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_memory_deletions")
public class UserMemoryDeletion {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "owner_id", nullable = false, columnDefinition = "CHAR(36)") private String ownerId;
    @Column(name = "memory_id", nullable = false, columnDefinition = "CHAR(36)") private String memoryId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private MemoryIndexStatus status;
    @Column(nullable = false) private int attempts;
    @Column(name = "failure_message") private String failureMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected UserMemoryDeletion() { }
    public UserMemoryDeletion(String ownerId, String memoryId) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.memoryId = memoryId;
        this.status = MemoryIndexStatus.QUEUED; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getMemoryId() { return memoryId; }
    public MemoryIndexStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public boolean begin() { if (status != MemoryIndexStatus.QUEUED) return false; status = MemoryIndexStatus.RUNNING; attempts++; updatedAt = Instant.now(); return true; }
    public void complete() { status = MemoryIndexStatus.READY; failureMessage = null; updatedAt = Instant.now(); }
    public void fail(String message, boolean retry) { status = retry ? MemoryIndexStatus.QUEUED : MemoryIndexStatus.FAILED; failureMessage = message; updatedAt = Instant.now(); }
}
