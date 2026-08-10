package com.voicenote.repository;

import com.voicenote.domain.AgentCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentCheckpointRepository extends JpaRepository<AgentCheckpoint, String> {
    List<AgentCheckpoint> findByKnowledgeRunIdOrderByCheckpointSequenceAsc(String runId);
    void deleteByKnowledgeRunId(String runId);
}
