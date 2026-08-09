package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_run_evidence", uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_evidence_source_ref", columnNames = {"knowledge_run_id", "result_path", "source_ref"}))
public class KnowledgeRunEvidence {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "knowledge_run_id", nullable = false, columnDefinition = "CHAR(36)") private String knowledgeRunId;
    @Enumerated(EnumType.STRING) @Column(name = "source_kind", nullable = false) private EvidenceSourceKind sourceKind;
    @Column(name = "source_ref") private String sourceRef;
    @Column(name = "knowledge_document_id", columnDefinition = "CHAR(36)") private String knowledgeDocumentId;
    @Column(name = "transcription_task_id", columnDefinition = "CHAR(36)") private String transcriptionTaskId;
    @Column(name = "knowledge_chunk_id", columnDefinition = "CHAR(36)") private String knowledgeChunkId;
    @Column(name = "result_path", nullable = false) private String resultPath;
    @Column(name = "transcript_segment_id", columnDefinition = "CHAR(36)") private String transcriptSegmentId;
    @Column(name = "external_label") private String externalLabel;
    @Column(name = "external_url") private String externalUrl;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected KnowledgeRunEvidence() { }
    public KnowledgeRunEvidence(String runId, String documentId, String chunkId, String resultPath, String segmentId) {
        this.id = UUID.randomUUID().toString(); this.knowledgeRunId = runId; this.knowledgeDocumentId = documentId;
        this.sourceKind = EvidenceSourceKind.TRANSCRIPT_SEGMENT; this.sourceRef = segmentId; this.knowledgeChunkId = chunkId;
        this.resultPath = resultPath; this.transcriptSegmentId = segmentId; this.createdAt = Instant.now();
    }
    public KnowledgeRunEvidence(String runId, EvidenceSourceKind kind, String sourceRef, String documentId, String taskId, String chunkId,
                                String resultPath, String segmentId, String externalLabel, String externalUrl) {
        this.id = UUID.randomUUID().toString(); this.knowledgeRunId = runId; this.sourceKind = kind; this.sourceRef = sourceRef;
        this.knowledgeDocumentId = documentId; this.transcriptionTaskId = taskId; this.knowledgeChunkId = chunkId;
        this.resultPath = resultPath; this.transcriptSegmentId = segmentId; this.externalLabel = externalLabel; this.externalUrl = externalUrl;
        this.createdAt = Instant.now();
    }
    public String getKnowledgeRunId() { return knowledgeRunId; }
    public EvidenceSourceKind getSourceKind() { return sourceKind; }
    public String getSourceRef() { return sourceRef; }
    public String getKnowledgeDocumentId() { return knowledgeDocumentId; }
    public String getTranscriptionTaskId() { return transcriptionTaskId; }
    public String getKnowledgeChunkId() { return knowledgeChunkId; }
    public String getResultPath() { return resultPath; }
    public String getTranscriptSegmentId() { return transcriptSegmentId; }
    public String getExternalLabel() { return externalLabel; }
    public String getExternalUrl() { return externalUrl; }
}
