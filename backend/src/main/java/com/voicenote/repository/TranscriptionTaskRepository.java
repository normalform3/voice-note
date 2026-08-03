package com.voicenote.repository;

import com.voicenote.domain.TranscriptionTask;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface TranscriptionTaskRepository extends JpaRepository<TranscriptionTask, String> {
    Optional<TranscriptionTask> findByOwnerIdAndAudioBlobIdAndAsrConfigHashAndPipelineVersion(String ownerId, String audioBlobId, String asrConfigHash, String pipelineVersion);
    List<TranscriptionTask> findByOwnerIdOrderByUpdatedAtDesc(String ownerId);
}
