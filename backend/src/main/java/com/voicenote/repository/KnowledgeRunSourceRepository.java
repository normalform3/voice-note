package com.voicenote.repository;

import com.voicenote.domain.KnowledgeRunSource;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KnowledgeRunSourceRepository extends JpaRepository<KnowledgeRunSource, String> {
    List<KnowledgeRunSource> findByKnowledgeRunIdOrderByCreatedAtAsc(String runId);
    boolean existsByKnowledgeRunIdAndSourceRef(String runId, String sourceRef);
    void deleteByKnowledgeRunId(String runId);
}
