package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_chunks", uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_chunk_index", columnNames = {"knowledge_document_id", "chunk_index"}))
public class KnowledgeChunk {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "knowledge_document_id", nullable = false, columnDefinition = "CHAR(36)") private String knowledgeDocumentId;
    @Column(name = "chunk_index", nullable = false) private int chunkIndex;
    @Column(name = "start_ms", nullable = false) private long startMs;
    @Column(name = "end_ms", nullable = false) private long endMs;
    @Column(name = "segment_ids", nullable = false, columnDefinition = "json") private String segmentIds;
    @Column(name = "organized_document_block_ids", columnDefinition = "json") private String organizedDocumentBlockIds;
    @Column(name = "text_content", nullable = false, columnDefinition = "TEXT") private String textContent;
    @Column(name = "content_hash", nullable = false, columnDefinition = "CHAR(64)") private String contentHash;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected KnowledgeChunk() { }
    public KnowledgeChunk(String documentId, int chunkIndex, long startMs, long endMs, String segmentIds, String textContent, String contentHash) {
        this.id = UUID.randomUUID().toString(); this.knowledgeDocumentId = documentId; this.chunkIndex = chunkIndex;
        this.startMs = startMs; this.endMs = endMs; this.segmentIds = segmentIds; this.textContent = textContent;
        this.contentHash = contentHash; this.createdAt = Instant.now();
    }
    public KnowledgeChunk(String documentId, int chunkIndex, long startMs, long endMs, String segmentIds, String blockIds, String textContent, String contentHash) {
        this(documentId, chunkIndex, startMs, endMs, segmentIds, textContent, contentHash);
        this.organizedDocumentBlockIds = blockIds;
    }
    public String getId() { return id; }
    public String getKnowledgeDocumentId() { return knowledgeDocumentId; }
    public int getChunkIndex() { return chunkIndex; }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public String getSegmentIds() { return segmentIds; }
    public String getOrganizedDocumentBlockIds() { return organizedDocumentBlockIds; }
    public String getTextContent() { return textContent; }
    public String getContentHash() { return contentHash; }
}
