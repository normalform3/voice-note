package com.voicenote.repository;

import com.voicenote.domain.RawTranscriptDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface RawTranscriptDocumentRepository extends JpaRepository<RawTranscriptDocument, String> {
    Optional<RawTranscriptDocument> findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion(String ownerId, String taskId, int transcriptVersion);
    List<RawTranscriptDocument> findByTranscriptionTaskId(String taskId);
    void deleteByTranscriptionTaskId(String taskId);
}
