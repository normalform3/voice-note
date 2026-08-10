package com.voicenote.repository;

import com.voicenote.domain.SpeakerCorrectionInvocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SpeakerCorrectionInvocationRepository extends JpaRepository<SpeakerCorrectionInvocation, String> {
    Optional<SpeakerCorrectionInvocation> findByRunIdAndChunkIndexAndAttemptNumber(String runId, int chunkIndex, int attemptNumber);
    void deleteByRunId(String runId);
}
