package com.voicenote.repository;

import com.voicenote.domain.AnalysisInvocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AnalysisInvocationRepository extends JpaRepository<AnalysisInvocation, String> {
    Optional<AnalysisInvocation> findByAnalysisRunIdAndStageNameAndChunkIndex(String runId, String stageName, int chunkIndex);
}
