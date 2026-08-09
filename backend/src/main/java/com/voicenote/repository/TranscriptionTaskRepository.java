package com.voicenote.repository;

import com.voicenote.domain.TranscriptionTask;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;

public interface TranscriptionTaskRepository extends JpaRepository<TranscriptionTask, String> {
    Optional<TranscriptionTask> findByOwnerIdAndAudioBlobIdAndAsrConfigHashAndPipelineVersion(String ownerId, String audioBlobId, String asrConfigHash, String pipelineVersion);
    List<TranscriptionTask> findByOwnerIdOrderByUpdatedAtDesc(String ownerId);
    @Query("select max(segment.endMs) from TranscriptSegment segment where segment.transcriptionTaskId = :taskId and segment.transcriptVersion = :transcriptVersion")
    Long findDurationMs(@Param("taskId") String taskId, @Param("transcriptVersion") int transcriptVersion);
    long countByAudioBlobId(String audioBlobId);
}
