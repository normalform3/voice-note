package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_index_versions", uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_index_generation", columnNames = {"knowledge_document_id", "generation"}))
public class KnowledgeIndexVersion {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Version private long version;
    @Column(name = "knowledge_document_id", nullable = false, columnDefinition = "CHAR(36)") private String knowledgeDocumentId;
    @Column(nullable = false) private int generation;
    @Column(name = "organized_document_id", nullable = false, columnDefinition = "CHAR(36)") private String organizedDocumentId;
    @Column(name = "organized_document_version", nullable = false) private long organizedDocumentVersion;
    @Column(name = "configuration_hash", nullable = false, columnDefinition = "CHAR(64)") private String configurationHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private KnowledgeIndexVersionStatus status;
    @Enumerated(EnumType.STRING) @Column(name = "current_stage") private KnowledgeIndexStage currentStage;
    @Column(nullable = false) private boolean active;
    @Column(name = "topic_count", nullable = false) private int topicCount;
    @Column(name = "chunk_count", nullable = false) private int chunkCount;
    @Column(name = "indexed_chunk_count", nullable = false) private int indexedChunkCount;
    @Column(name = "failure_message") private String failureMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected KnowledgeIndexVersion() { }
    public KnowledgeIndexVersion(String documentId, int generation, String organizedDocumentId, long organizedDocumentVersion, String configurationHash) {
        this.id = UUID.randomUUID().toString(); this.knowledgeDocumentId = documentId; this.generation = generation;
        this.organizedDocumentId = organizedDocumentId; this.organizedDocumentVersion = organizedDocumentVersion; this.configurationHash = configurationHash;
        this.status = KnowledgeIndexVersionStatus.PENDING; this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public String getKnowledgeDocumentId() { return knowledgeDocumentId; }
    public int getGeneration() { return generation; }
    public String getOrganizedDocumentId() { return organizedDocumentId; }
    public long getOrganizedDocumentVersion() { return organizedDocumentVersion; }
    public String getConfigurationHash() { return configurationHash; }
    public KnowledgeIndexVersionStatus getStatus() { return status; }
    public KnowledgeIndexStage getCurrentStage() { return currentStage; }
    public boolean isActive() { return active; }
    public int getTopicCount() { return topicCount; }
    public int getChunkCount() { return chunkCount; }
    public int getIndexedChunkCount() { return indexedChunkCount; }
    public String getFailureMessage() { return failureMessage; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void queue() { if (status == KnowledgeIndexVersionStatus.PENDING || status == KnowledgeIndexVersionStatus.FAILED) { status = KnowledgeIndexVersionStatus.QUEUED; failureMessage = null; updatedAt = Instant.now(); } }
    public boolean begin() { if (status != KnowledgeIndexVersionStatus.QUEUED) return false; status = KnowledgeIndexVersionStatus.INDEXING; updatedAt = Instant.now(); return true; }
    public void stage(KnowledgeIndexStage value) { currentStage = value; updatedAt = Instant.now(); }
    public void topicsCreated(int count) { topicCount = count; updatedAt = Instant.now(); }
    public void chunksCreated(int count) { chunkCount = count; updatedAt = Instant.now(); }
    public void indexed(int count) { indexedChunkCount = count; updatedAt = Instant.now(); }
    public void ready() { status = KnowledgeIndexVersionStatus.READY; currentStage = null; failureMessage = null; updatedAt = Instant.now(); }
    public void activate() { active = true; updatedAt = Instant.now(); }
    public void retire() { active = false; status = KnowledgeIndexVersionStatus.RETIRED; updatedAt = Instant.now(); }
    public void fail(String message) { status = KnowledgeIndexVersionStatus.FAILED; failureMessage = message; updatedAt = Instant.now(); }
}
