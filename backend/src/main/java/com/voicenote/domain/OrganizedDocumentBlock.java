package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "organized_document_blocks", uniqueConstraints = @UniqueConstraint(name = "uk_organized_block_index", columnNames = {"organized_document_id", "block_index"}))
public class OrganizedDocumentBlock {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "organized_document_id", nullable = false, columnDefinition = "CHAR(36)") private String organizedDocumentId;
    @Column(name = "block_index", nullable = false) private int blockIndex;
    @Enumerated(EnumType.STRING) @Column(name = "block_type", nullable = false) private OrganizedBlockType blockType;
    @Column(name = "speaker_label") private String speakerLabel;
    @Column(name = "topic_title") private String topicTitle;
    @Column(name = "start_ms", nullable = false) private long startMs;
    @Column(name = "end_ms", nullable = false) private long endMs;
    @Column(name = "source_segment_ids", nullable = false, columnDefinition = "json") private String sourceSegmentIds;
    @Column(name = "text_content", nullable = false, columnDefinition = "TEXT") private String textContent;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected OrganizedDocumentBlock() { }
    public OrganizedDocumentBlock(String documentId, int index, OrganizedBlockType type, String speaker, String topic, long startMs, long endMs, String sourceSegmentIds, String text) {
        this.id = UUID.randomUUID().toString(); this.organizedDocumentId = documentId; this.blockIndex = index; this.blockType = type;
        this.speakerLabel = speaker; this.topicTitle = topic; this.startMs = startMs; this.endMs = endMs;
        this.sourceSegmentIds = sourceSegmentIds; this.textContent = text; this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getOrganizedDocumentId() { return organizedDocumentId; }
    public int getBlockIndex() { return blockIndex; }
    public OrganizedBlockType getBlockType() { return blockType; }
    public String getSpeakerLabel() { return speakerLabel; }
    public String getTopicTitle() { return topicTitle; }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
    public String getSourceSegmentIds() { return sourceSegmentIds; }
    public String getTextContent() { return textContent; }
}
