package com.voicenote.repository;

import com.voicenote.domain.KnowledgeRunStep;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KnowledgeRunStepRepository extends JpaRepository<KnowledgeRunStep, String> {
    List<KnowledgeRunStep> findByKnowledgeRunIdOrderByStepIndexAsc(String runId);
    long countByKnowledgeRunId(String runId);
    void deleteByKnowledgeRunId(String runId);
}
