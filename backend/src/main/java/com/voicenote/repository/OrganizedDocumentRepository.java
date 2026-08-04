package com.voicenote.repository;

import com.voicenote.domain.OrganizedDocument;
import com.voicenote.domain.OrganizedDocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface OrganizedDocumentRepository extends JpaRepository<OrganizedDocument, String> {
    Optional<OrganizedDocument> findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion(String ownerId, String taskId, int transcriptVersion);
    Optional<OrganizedDocument> findTopByOwnerIdAndTranscriptionTaskIdOrderByUpdatedAtDesc(String ownerId, String taskId);
    Optional<OrganizedDocument> findTopByTranscriptionTaskIdOrderByUpdatedAtDesc(String taskId);
    List<OrganizedDocument> findTop20ByStatusOrderByCreatedAtAsc(OrganizedDocumentStatus status);
    List<OrganizedDocument> findByTranscriptionTaskId(String taskId);
    void deleteByTranscriptionTaskId(String taskId);
}
