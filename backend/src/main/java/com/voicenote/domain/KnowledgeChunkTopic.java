package com.voicenote.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "knowledge_chunk_topics", uniqueConstraints = @UniqueConstraint(name = "uk_knowledge_chunk_topic", columnNames = {"knowledge_chunk_id", "knowledge_topic_id"}))
public class KnowledgeChunkTopic {
    @Id @Column(columnDefinition = "CHAR(36)") private String id;
    @Column(name = "knowledge_chunk_id", nullable = false, columnDefinition = "CHAR(36)") private String knowledgeChunkId;
    @Column(name = "knowledge_topic_id", nullable = false, columnDefinition = "CHAR(36)") private String knowledgeTopicId;
    @Column(name = "topic_order_in_chunk", nullable = false) private int topicOrderInChunk;
    @Column(name = "chunk_index_in_topic", nullable = false) private int chunkIndexInTopic;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    protected KnowledgeChunkTopic() { }
    public KnowledgeChunkTopic(String chunkId, String topicId, int topicOrderInChunk, int chunkIndexInTopic) {
        this.id = UUID.randomUUID().toString(); this.knowledgeChunkId = chunkId; this.knowledgeTopicId = topicId;
        this.topicOrderInChunk = topicOrderInChunk; this.chunkIndexInTopic = chunkIndexInTopic; this.createdAt = Instant.now();
    }
    public String getKnowledgeChunkId() { return knowledgeChunkId; }
    public String getKnowledgeTopicId() { return knowledgeTopicId; }
    public int getTopicOrderInChunk() { return topicOrderInChunk; }
    public int getChunkIndexInTopic() { return chunkIndexInTopic; }
}
