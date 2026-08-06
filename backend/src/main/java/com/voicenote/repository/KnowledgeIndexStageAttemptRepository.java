package com.voicenote.repository;

import com.voicenote.domain.KnowledgeIndexStage;
import com.voicenote.domain.KnowledgeIndexStageAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface KnowledgeIndexStageAttemptRepository extends JpaRepository<KnowledgeIndexStageAttempt, String> {
    List<KnowledgeIndexStageAttempt> findByKnowledgeIndexVersionIdOrderByQueuedAtAsc(String indexVersionId);
    Optional<KnowledgeIndexStageAttempt> findTopByKnowledgeIndexVersionIdAndStageOrderByAttemptNumberDesc(String indexVersionId, KnowledgeIndexStage stage);
    void deleteByKnowledgeIndexVersionId(String indexVersionId);
}
