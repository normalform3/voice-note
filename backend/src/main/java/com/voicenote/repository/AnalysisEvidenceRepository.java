package com.voicenote.repository;

import com.voicenote.domain.AnalysisEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AnalysisEvidenceRepository extends JpaRepository<AnalysisEvidence, String> {
    List<AnalysisEvidence> findByAnalysisRunId(String runId);
}
