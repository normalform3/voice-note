package com.voicenote.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_memory_candidates", uniqueConstraints = @UniqueConstraint(
        name = "uk_user_memory_candidate_turn_key", columnNames = {"source_turn_id", "semantic_key"}))
public class UserMemoryCandidate {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "owner_id", nullable = false, columnDefinition = "CHAR(36)") private String ownerId;
    @Column(name = "source_turn_id", columnDefinition = "CHAR(36)") private String sourceTurnId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private UserMemoryCategory category;
    @Column(name = "semantic_key", nullable = false, length = 160) private String semanticKey;
    @Column(nullable = false, columnDefinition = "TEXT") private String content;
    @Column(name = "source_excerpt", nullable = false, length = 2000) private String sourceExcerpt;
    @Column(nullable = false, precision = 5, scale = 4) private BigDecimal confidence;
    @Enumerated(EnumType.STRING) @Column(name = "change_type", nullable = false) private UserMemoryChangeType changeType;
    @Column(name = "target_memory_id", columnDefinition = "CHAR(36)") private String targetMemoryId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private UserMemoryCandidateStatus status;
    @Column(name = "extraction_version", nullable = false, length = 64) private String extractionVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected UserMemoryCandidate() { }
    public UserMemoryCandidate(String ownerId, String sourceTurnId, UserMemoryCategory category, String semanticKey,
                               String content, String sourceExcerpt, double confidence, UserMemoryChangeType changeType,
                               String targetMemoryId, UserMemoryCandidateStatus status, String extractionVersion) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.sourceTurnId = sourceTurnId;
        this.category = category; this.semanticKey = semanticKey; this.content = content; this.sourceExcerpt = sourceExcerpt;
        this.confidence = BigDecimal.valueOf(confidence).setScale(4, RoundingMode.HALF_UP);
        this.changeType = changeType; this.targetMemoryId = targetMemoryId;
        this.status = status; this.extractionVersion = extractionVersion; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getSourceTurnId() { return sourceTurnId; }
    public UserMemoryCategory getCategory() { return category; }
    public String getSemanticKey() { return semanticKey; }
    public String getContent() { return content; }
    public String getSourceExcerpt() { return sourceExcerpt; }
    public double getConfidence() { return confidence.doubleValue(); }
    public UserMemoryChangeType getChangeType() { return changeType; }
    public String getTargetMemoryId() { return targetMemoryId; }
    public UserMemoryCandidateStatus getStatus() { return status; }
    public String getExtractionVersion() { return extractionVersion; }
    public Instant getCreatedAt() { return createdAt; }
    public void confirm(String editedContent) { if (editedContent != null) content = editedContent; status = UserMemoryCandidateStatus.CONFIRMED; updatedAt = Instant.now(); }
    public void reject() { status = UserMemoryCandidateStatus.REJECTED; updatedAt = Instant.now(); }
    public void retry() { if (status == UserMemoryCandidateStatus.REJECTED) { status = UserMemoryCandidateStatus.PENDING; updatedAt = Instant.now(); } }
    public void duplicate() { status = UserMemoryCandidateStatus.DUPLICATE; updatedAt = Instant.now(); }
}
