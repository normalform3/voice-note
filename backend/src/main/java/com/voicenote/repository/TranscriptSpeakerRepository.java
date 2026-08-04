package com.voicenote.repository;

import com.voicenote.domain.TranscriptSpeaker;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface TranscriptSpeakerRepository extends JpaRepository<TranscriptSpeaker, String> {
    List<TranscriptSpeaker> findByTranscriptionTaskIdAndTranscriptVersionOrderByAsrSpeakerId(String taskId, int transcriptVersion);
    Optional<TranscriptSpeaker> findByTranscriptionTaskIdAndTranscriptVersionAndAsrSpeakerId(String taskId, int transcriptVersion, String asrSpeakerId);
    void deleteByTranscriptionTaskId(String taskId);
}
