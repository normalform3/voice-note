package com.voicenote.repository;

import com.voicenote.domain.AnalysisRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;
import com.voicenote.domain.AnalysisRunStatus;

public interface AnalysisRunRepository extends JpaRepository<AnalysisRun, String> {
    Optional<AnalysisRun> findByOwnerIdAndTranscriptionTaskIdAndSemanticHash(String ownerId, String taskId, String semanticHash);
    List<AnalysisRun> findTop10ByStatusOrderByCreatedAtAsc(AnalysisRunStatus status);
    List<AnalysisRun> findByOwnerIdOrderByCreatedAtDesc(String ownerId);
    List<AnalysisRun> findByTranscriptionTaskId(String taskId);
    void deleteByTranscriptionTaskId(String taskId);
}
