package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organized_documents", uniqueConstraints = @UniqueConstraint(name = "uk_organized_document_source", columnNames = {"owner_id", "transcription_task_id", "transcript_version"}))
public class OrganizedDocument {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Version private long version;
    @Column(name = "owner_id", nullable = false, columnDefinition = "CHAR(36)") private String ownerId;
    @Column(name = "transcription_task_id", nullable = false, columnDefinition = "CHAR(36)") private String transcriptionTaskId;
    @Column(name = "transcript_version", nullable = false) private int transcriptVersion;
    @Column(nullable = false) private String title;
    @Column(name = "summary_text", columnDefinition = "TEXT") private String summaryText;
    @Column(name = "organization_mode", nullable = false) private String organizationMode = "RULES";
    @Enumerated(EnumType.STRING) @Column(nullable = false) private OrganizedDocumentStatus status;
    @Column(name = "structure_document", columnDefinition = "json") private String structureDocument;
    @Column(name = "plain_text", columnDefinition = "MEDIUMTEXT") private String plainText;
    @Column(name = "failure_message") private String failureMessage;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected OrganizedDocument() { }
    public OrganizedDocument(String ownerId, String taskId, int transcriptVersion, String title) {
        this.id = UUID.randomUUID().toString(); this.ownerId = ownerId; this.transcriptionTaskId = taskId;
        this.transcriptVersion = transcriptVersion; this.title = title; this.status = OrganizedDocumentStatus.PENDING;
        this.createdAt = Instant.now(); this.updatedAt = createdAt;
    }
    public String getId() { return id; }
    public long getVersion() { return version; }
    public String getOwnerId() { return ownerId; }
    public String getTranscriptionTaskId() { return transcriptionTaskId; }
    public int getTranscriptVersion() { return transcriptVersion; }
    public String getTitle() { return title; }
    public String getSummaryText() { return summaryText; }
    public String getOrganizationMode() { return organizationMode; }
    public OrganizedDocumentStatus getStatus() { return status; }
    public String getStructureDocument() { return structureDocument; }
    public String getPlainText() { return plainText; }
    public String getFailureMessage() { return failureMessage; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void queue() { if (status == OrganizedDocumentStatus.PENDING || status == OrganizedDocumentStatus.FAILED || status == OrganizedDocumentStatus.STALE) { status = OrganizedDocumentStatus.QUEUED; failureMessage = null; updatedAt = Instant.now(); } }
    public boolean begin() { if (status != OrganizedDocumentStatus.QUEUED) return false; status = OrganizedDocumentStatus.ORGANIZING; updatedAt = Instant.now(); return true; }
    public void ready(String structure, String text) { status = OrganizedDocumentStatus.READY; structureDocument = structure; plainText = text; failureMessage = null; updatedAt = Instant.now(); }
    public void ready(String title, String summary, String mode, String structure, String text) {
        this.title = title; this.summaryText = summary; this.organizationMode = mode; ready(structure, text);
    }
    public void fail(String message) { status = OrganizedDocumentStatus.FAILED; failureMessage = message; updatedAt = Instant.now(); }
    public void stale() { status = OrganizedDocumentStatus.STALE; failureMessage = null; updatedAt = Instant.now(); }
    public boolean recover() { if (status != OrganizedDocumentStatus.ORGANIZING) return false; status = OrganizedDocumentStatus.QUEUED; updatedAt = Instant.now(); return true; }
}
