package com.voicenote.domain;

import com.voicenote.agent.AgentEvidenceLedger;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_run_sources", uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_run_source_ref", columnNames = {"knowledge_run_id", "source_ref"}))
public class KnowledgeRunSource {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "knowledge_run_id", nullable = false, columnDefinition = "CHAR(36)") private String knowledgeRunId;
    @Column(name = "source_ref", nullable = false) private String sourceRef;
    @Enumerated(EnumType.STRING) @Column(name = "source_kind", nullable = false) private EvidenceSourceKind sourceKind;
    @Column(name = "knowledge_document_id", columnDefinition = "CHAR(36)") private String knowledgeDocumentId;
    @Column(name = "transcription_task_id", columnDefinition = "CHAR(36)") private String transcriptionTaskId;
    @Column(name = "knowledge_chunk_id", columnDefinition = "CHAR(36)") private String knowledgeChunkId;
    @Column(name = "transcript_segment_id", columnDefinition = "CHAR(36)") private String transcriptSegmentId;
    @Column(length = 512) private String topic;
    @Column(name = "speaker_id") private String speakerId;
    @Column(name = "start_ms") private Long startMs;
    @Column(name = "end_ms") private Long endMs;
    @Column(name = "text_content", columnDefinition = "TEXT") private String textContent;
    @Column(name = "external_label", length = 512) private String externalLabel;
    @Column(name = "external_url", length = 1000) private String externalUrl;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected KnowledgeRunSource() { }
    public KnowledgeRunSource(String runId, AgentEvidenceLedger.EvidenceSource source) {
        this.id = UUID.randomUUID().toString(); this.knowledgeRunId = runId; this.sourceRef = source.ref(); this.sourceKind = source.kind();
        this.knowledgeDocumentId = source.documentId(); this.transcriptionTaskId = source.taskId(); this.knowledgeChunkId = source.chunkId();
        this.transcriptSegmentId = source.segmentId(); this.topic = source.topic(); this.speakerId = source.speakerId(); this.startMs = source.startMs();
        this.endMs = source.endMs(); this.textContent = source.text(); this.externalLabel = source.label(); this.externalUrl = source.url(); this.createdAt = Instant.now();
    }
    public String getSourceRef() { return sourceRef; }
    public AgentEvidenceLedger.EvidenceSource toEvidenceSource() {
        return new AgentEvidenceLedger.EvidenceSource(sourceRef, sourceKind, knowledgeDocumentId, transcriptionTaskId, knowledgeChunkId,
                transcriptSegmentId, topic, speakerId, startMs, endMs, textContent, externalLabel, externalUrl);
    }
}
