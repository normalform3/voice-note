package com.echotrace.repository;

import com.echotrace.domain.AnalysisEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AnalysisEvidenceRepository extends JpaRepository<AnalysisEvidence, String> {
    List<AnalysisEvidence> findByAnalysisRunId(String runId);
}
