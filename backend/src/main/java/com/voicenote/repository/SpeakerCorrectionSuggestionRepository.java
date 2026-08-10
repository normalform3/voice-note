package com.voicenote.repository;

import com.voicenote.domain.SpeakerCorrectionSuggestion;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SpeakerCorrectionSuggestionRepository extends JpaRepository<SpeakerCorrectionSuggestion, String> {
    List<SpeakerCorrectionSuggestion> findByRunIdOrderBySuggestionIndex(String runId);
    void deleteByRunId(String runId);
}
