package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_memories", uniqueConstraints = @UniqueConstraint(
        name = "uk_user_memory_semantic_key", columnNames = {"owner_id", "semantic_key"}))
public class UserMemory {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Version private long version;
    @Column(name = "owner_id", nullable = false, columnDefinition = "CHAR(36)") private String ownerId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private UserMemoryCategory category;
    @Column(name = "semantic_key", nullable = false, length = 160) private String semanticKey;
    @Column(name = "current_version_id", columnDefinition = "CHAR(36)") private String currentVersionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private UserMemoryStatus status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected UserMemory() { }
    public UserMemory(String ownerId, UserMemoryCategory category, String semanticKey) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.category = category;
        this.semanticKey = semanticKey; this.status = UserMemoryStatus.ACTIVE;
        this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public UserMemoryCategory getCategory() { return category; }
    public String getSemanticKey() { return semanticKey; }
    public String getCurrentVersionId() { return currentVersionId; }
    public UserMemoryStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void useVersion(String versionId) { currentVersionId = versionId; updatedAt = Instant.now(); }
    public void clearVersion() { currentVersionId = null; updatedAt = Instant.now(); }
    public void changeCategory(UserMemoryCategory category) { this.category = category; updatedAt = Instant.now(); }
}
