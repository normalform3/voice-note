package com.voicenote.repository;

import com.voicenote.domain.SpeakerCorrectionRun;
import com.voicenote.domain.SpeakerCorrectionRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SpeakerCorrectionRunRepository extends JpaRepository<SpeakerCorrectionRun, String> {
    Optional<SpeakerCorrectionRun> findTopByOwnerIdAndTranscriptionTaskIdOrderByCreatedAtDesc(String ownerId, String taskId);
    List<SpeakerCorrectionRun> findByTranscriptionTaskId(String taskId);
    List<SpeakerCorrectionRun> findTop10ByStatusOrderByCreatedAtAsc(SpeakerCorrectionRunStatus status);
    void deleteByTranscriptionTaskId(String taskId);
}
