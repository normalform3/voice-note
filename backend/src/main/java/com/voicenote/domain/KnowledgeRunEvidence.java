package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_run_evidence", uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_evidence_path_segment", columnNames = {"knowledge_run_id", "result_path", "transcript_segment_id"}))
public class KnowledgeRunEvidence {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "knowledge_run_id", nullable = false, columnDefinition = "CHAR(36)") private String knowledgeRunId;
    @Column(name = "knowledge_document_id", nullable = false, columnDefinition = "CHAR(36)") private String knowledgeDocumentId;
    @Column(name = "knowledge_chunk_id", nullable = false, columnDefinition = "CHAR(36)") private String knowledgeChunkId;
    @Column(name = "result_path", nullable = false) private String resultPath;
    @Column(name = "transcript_segment_id", nullable = false, columnDefinition = "CHAR(36)") private String transcriptSegmentId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected KnowledgeRunEvidence() { }
    public KnowledgeRunEvidence(String runId, String documentId, String chunkId, String resultPath, String segmentId) {
        this.id = UUID.randomUUID().toString(); this.knowledgeRunId = runId; this.knowledgeDocumentId = documentId;
        this.knowledgeChunkId = chunkId; this.resultPath = resultPath; this.transcriptSegmentId = segmentId; this.createdAt = Instant.now();
    }
    public String getKnowledgeRunId() { return knowledgeRunId; }
    public String getKnowledgeDocumentId() { return knowledgeDocumentId; }
    public String getKnowledgeChunkId() { return knowledgeChunkId; }
    public String getResultPath() { return resultPath; }
    public String getTranscriptSegmentId() { return transcriptSegmentId; }
}
