package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_run_documents", uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_run_document", columnNames = {"knowledge_run_id", "transcription_task_id"}))
public class KnowledgeRunDocument {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "knowledge_run_id", nullable = false, columnDefinition = "CHAR(36)") private String knowledgeRunId;
    @Column(name = "transcription_task_id", nullable = false, columnDefinition = "CHAR(36)") private String transcriptionTaskId;
    @Column(name = "knowledge_document_id", columnDefinition = "CHAR(36)") private String knowledgeDocumentId;
    @Column(name = "knowledge_index_version_id", columnDefinition = "CHAR(36)") private String knowledgeIndexVersionId;
    @Column(name = "metadata_snapshot", nullable = false, columnDefinition = "json") private String metadataSnapshot;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected KnowledgeRunDocument() { }

    public KnowledgeRunDocument(String runId, String taskId, String documentId, String indexVersionId, String metadataSnapshot) {
        this.id = UUID.randomUUID().toString(); this.knowledgeRunId = runId; this.transcriptionTaskId = taskId;
        this.knowledgeDocumentId = documentId; this.knowledgeIndexVersionId = indexVersionId;
        this.metadataSnapshot = metadataSnapshot; this.createdAt = Instant.now();
    }

    public String getId() { return id; }
    public String getKnowledgeRunId() { return knowledgeRunId; }
    public String getTranscriptionTaskId() { return transcriptionTaskId; }
    public String getKnowledgeDocumentId() { return knowledgeDocumentId; }
    public String getKnowledgeIndexVersionId() { return knowledgeIndexVersionId; }
    public String getMetadataSnapshot() { return metadataSnapshot; }
}
