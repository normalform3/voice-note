package com.voicenote.repository;

import com.voicenote.domain.KnowledgeTopic;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KnowledgeTopicRepository extends JpaRepository<KnowledgeTopic, String> {
    List<KnowledgeTopic> findByKnowledgeIndexVersionIdOrderByTopicIndex(String indexVersionId);
    void deleteByKnowledgeIndexVersionId(String indexVersionId);
}
