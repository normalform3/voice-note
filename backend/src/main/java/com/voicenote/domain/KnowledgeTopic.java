package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_topics", uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_topic_index", columnNames = {"knowledge_index_version_id", "topic_index"}))
public class KnowledgeTopic {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "knowledge_index_version_id", nullable = false, columnDefinition = "CHAR(36)") private String knowledgeIndexVersionId;
    @Column(name = "source_topic_block_id", columnDefinition = "CHAR(36)") private String sourceTopicBlockId;
    @Column(name = "topic_index", nullable = false) private int topicIndex;
    @Column(nullable = false) private String title;
    @Column(name = "text_content", nullable = false, columnDefinition = "MEDIUMTEXT") private String textContent;
    @Column(name = "speaker_ids", columnDefinition = "json") private String speakerIds;
    @Column(name = "source_segment_ids", nullable = false, columnDefinition = "json") private String sourceSegmentIds;
    @Column(name = "source_fragments", columnDefinition = "json") private String sourceFragments;
    @Column(name = "source_unit_snapshots", nullable = false, columnDefinition = "json") private String sourceUnitSnapshots;
    @Column(name = "start_ms", nullable = false) private long startMs;
    @Column(name = "end_ms", nullable = false) private long endMs;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected KnowledgeTopic() { }
    public KnowledgeTopic(String indexVersionId, String sourceTopicBlockId, int topicIndex, String title, String textContent, String speakerIds,
                          String sourceSegmentIds, String sourceFragments, String sourceUnitSnapshots, long startMs, long endMs) {
        this.id = UUID.randomUUID().toString(); this.knowledgeIndexVersionId = indexVersionId; this.sourceTopicBlockId = sourceTopicBlockId;
        this.topicIndex = topicIndex; this.title = title; this.textContent = textContent; this.speakerIds = speakerIds;
        this.sourceSegmentIds = sourceSegmentIds; this.sourceFragments = sourceFragments; this.sourceUnitSnapshots = sourceUnitSnapshots;
        this.startMs = startMs; this.endMs = endMs; this.createdAt = Instant.now();
    }
    public String getId() { return id; }
    public String getKnowledgeIndexVersionId() { return knowledgeIndexVersionId; }
    public String getSourceTopicBlockId() { return sourceTopicBlockId; }
    public int getTopicIndex() { return topicIndex; }
    public String getTitle() { return title; }
    public String getTextContent() { return textContent; }
    public String getSpeakerIds() { return speakerIds; }
    public String getSourceSegmentIds() { return sourceSegmentIds; }
    public String getSourceFragments() { return sourceFragments; }
    public String getSourceUnitSnapshots() { return sourceUnitSnapshots; }
    public long getStartMs() { return startMs; }
    public long getEndMs() { return endMs; }
}
