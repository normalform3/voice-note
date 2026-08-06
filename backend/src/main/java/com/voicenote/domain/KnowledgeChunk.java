package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_chunks", uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_chunk_index", columnNames = {"knowledge_document_id", "chunk_index"}))
public class KnowledgeChunk {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "knowledge_document_id", nullable = false, columnDefinition = "CHAR(36)") private String knowledgeDocumentId;
    @Column(name = "knowledge_index_version_id", columnDefinition = "CHAR(36)") private String knowledgeIndexVersionId;
    @Column(name = "chunk_index", nullable = false) private int chunkIndex;
    @Column(name = "start_ms", nullable = false) private long startMs;
    @Column(name = "end_ms", nullable = false) private long endMs;
    @Column(name = "segment_ids", nullable = false, columnDefinition = "json") private String segmentIds;
    @Column(name = "organized_document_block_ids", columnDefinition = "json") private String organizedDocumentBlockIds;
    @Column(name = "topic_title") private String topicTitle;
    @Column(name = "speaker_ids", columnDefinition = "json") private String speakerIds;
    @Column(name = "source_fragments", columnDefinition = "json") private String sourceFragments;
    @Column(name = "context_segment_ids", columnDefinition = "json") private String contextSegmentIds;
    @Column(name = "token_count") private Integer tokenCount;
    @Column(name = "oversized", nullable = false) private boolean oversized;
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
    public KnowledgeChunk(String documentId, int chunkIndex, long startMs, long endMs, String segmentIds, String blockIds, String topicTitle,
                          String speakerIds, String sourceFragments, String contextSegmentIds, Integer tokenCount, boolean oversized, String textContent, String contentHash) {
        this(documentId, chunkIndex, startMs, endMs, segmentIds, blockIds, textContent, contentHash);
        this.topicTitle = topicTitle; this.speakerIds = speakerIds; this.sourceFragments = sourceFragments;
        this.contextSegmentIds = contextSegmentIds; this.tokenCount = tokenCount; this.oversized = oversized;
    }
    public KnowledgeChunk(String documentId, String indexVersionId, int chunkIndex, long startMs, long endMs, String segmentIds, String blockIds, String topicTitle,
                          String speakerIds, String sourceFragments, String contextSegmentIds, Integer tokenCount, boolean oversized, String textContent, String contentHash) {
        this(documentId, chunkIndex, startMs, endMs, segmentIds, blockIds, topicTitle, speakerIds, sourceFragments, contextSegmentIds, tokenCount, oversized, textContent, contentHash);
        this.knowledgeIndexVersionId = indexVersionId;
    }
    public String getId() { return id; }
    public String getKnowledgeDocumentId() { return knowledgeDocumentId; }
    public String getKnowledgeIndexVersionId() { return knowledgeIndexVersionId; }
    public int getChunkIndex() { return chunkIndex; }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public String getSegmentIds() { return segmentIds; }
    public String getOrganizedDocumentBlockIds() { return organizedDocumentBlockIds; }
    public String getTopicTitle() { return topicTitle; }
    public String getSpeakerIds() { return speakerIds; }
    public String getSourceFragments() { return sourceFragments; }
    public String getContextSegmentIds() { return contextSegmentIds; }
    public Integer getTokenCount() { return tokenCount; }
    public boolean isOversized() { return oversized; }
    public String getTextContent() { return textContent; }
    public String getContentHash() { return contentHash; }
}
