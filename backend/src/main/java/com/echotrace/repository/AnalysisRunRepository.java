package com.echotrace.repository;

import com.echotrace.domain.AnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;
import com.echotrace.domain.AnalysisRunStatus;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, String> {
    Optional<AnalysisRun> findByOwnerIdAndTranscriptionTaskIdAndSemanticHash(String ownerId, String taskId, String semanticHash);
    List<AnalysisRun> findTop10ByStatusOrderByCreatedAtAsc(AnalysisRunStatus status);
}
