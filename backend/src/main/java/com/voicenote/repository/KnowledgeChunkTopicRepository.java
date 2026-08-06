package com.voicenote.repository;

import com.voicenote.domain.KnowledgeChunkTopic;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface KnowledgeChunkTopicRepository extends JpaRepository<KnowledgeChunkTopic, String> {
    List<KnowledgeChunkTopic> findByKnowledgeChunkIdIn(Collection<String> chunkIds);
    List<KnowledgeChunkTopic> findByKnowledgeTopicIdIn(Collection<String> topicIds);
    void deleteByKnowledgeChunkIdIn(Collection<String> chunkIds);
}
