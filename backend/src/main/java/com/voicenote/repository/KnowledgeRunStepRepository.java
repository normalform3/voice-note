package com.voicenote.repository;

import com.voicenote.domain.KnowledgeRunStep;
import com.voicenote.domain.AgentStepStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KnowledgeRunStepRepository extends JpaRepository<KnowledgeRunStep, String> {
    List<KnowledgeRunStep> findByKnowledgeRunIdOrderByStepIndexAsc(String runId);
    List<KnowledgeRunStep> findByKnowledgeRunIdAndStatus(String runId, AgentStepStatus status);
    long countByKnowledgeRunId(String runId);
    void deleteByKnowledgeRunId(String runId);
}
