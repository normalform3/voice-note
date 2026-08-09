package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_documents", uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_document_source", columnNames = {"owner_id", "transcription_task_id", "transcript_version"}))
public class KnowledgeDocument {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Version private long version;
    @Column(name = "owner_id", nullable = false, columnDefinition = "CHAR(36)") private String ownerId;
    @Column(name = "transcription_task_id", nullable = false, columnDefinition = "CHAR(36)") private String transcriptionTaskId;
    @Column(name = "organized_document_id", columnDefinition = "CHAR(36)") private String organizedDocumentId;
    @Column(name = "transcript_version", nullable = false) private int transcriptVersion;
    @Column(name = "organized_document_version") private Integer organizedDocumentVersion;
    @Column(name = "active_index_version_id", columnDefinition = "CHAR(36)") private String activeIndexVersionId;
    @Column(nullable = false) private String title;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private KnowledgeDocumentStatus status;
    @Column(name = "failure_message") private String failureMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected KnowledgeDocument() { }
    public KnowledgeDocument(String ownerId, String transcriptionTaskId, int transcriptVersion, String title) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.transcriptionTaskId = transcriptionTaskId;
        this.transcriptVersion = transcriptVersion; this.title = title; this.status = KnowledgeDocumentStatus.PENDING;
        this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public KnowledgeDocument(String ownerId, String transcriptionTaskId, int transcriptVersion, String title, String organizedDocumentId, int organizedDocumentVersion) {
        this(ownerId, transcriptionTaskId, transcriptVersion, title);
        this.organizedDocumentId = organizedDocumentId; this.organizedDocumentVersion = organizedDocumentVersion;
    }
    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getTranscriptionTaskId() { return transcriptionTaskId; }
    public String getOrganizedDocumentId() { return organizedDocumentId; }
    public int getTranscriptVersion() { return transcriptVersion; }
    public Integer getOrganizedDocumentVersion() { return organizedDocumentVersion; }
    public String getActiveIndexVersionId() { return activeIndexVersionId; }
    public String getTitle() { return title; }
    public KnowledgeDocumentStatus getStatus() { return status; }
    public String getFailureMessage() { return failureMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void queue() { if (status == KnowledgeDocumentStatus.PENDING) { status = KnowledgeDocumentStatus.QUEUED; updatedAt = Instant.now(); } }
    public boolean beginIndexing() { if (status != KnowledgeDocumentStatus.QUEUED) return false; status = KnowledgeDocumentStatus.INDEXING; updatedAt = Instant.now(); return true; }
    public void ready() { status = KnowledgeDocumentStatus.READY; failureMessage = null; updatedAt = Instant.now(); }
    public void fail(String message) { status = KnowledgeDocumentStatus.FAILED; failureMessage = message; updatedAt = Instant.now(); }
    public void stale() { status = KnowledgeDocumentStatus.STALE; activeIndexVersionId = null; failureMessage = null; updatedAt = Instant.now(); }
    public void refreshSource(String organizedDocumentId, int organizedDocumentVersion, String title) {
        this.organizedDocumentId = organizedDocumentId; this.organizedDocumentVersion = organizedDocumentVersion; this.title = title;
        this.status = KnowledgeDocumentStatus.PENDING; this.activeIndexVersionId = null; this.failureMessage = null; this.updatedAt = Instant.now();
    }
    public boolean retry() { if (status != KnowledgeDocumentStatus.FAILED) return false; status = KnowledgeDocumentStatus.PENDING; failureMessage = null; updatedAt = Instant.now(); return true; }
    public boolean recover() { if (status != KnowledgeDocumentStatus.INDEXING) return false; status = KnowledgeDocumentStatus.QUEUED; updatedAt = Instant.now(); return true; }
    public void activateIndexVersion(String indexVersionId) { activeIndexVersionId = indexVersionId; ready(); }
    public boolean hasActiveIndexVersion() { return activeIndexVersionId != null; }
    public void clearActiveIndexVersion() { activeIndexVersionId = null; updatedAt = Instant.now(); }
}
