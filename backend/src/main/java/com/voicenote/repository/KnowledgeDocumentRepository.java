package com.voicenote.repository;

import com.voicenote.domain.KnowledgeDocument;
import com.voicenote.domain.KnowledgeDocumentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, String> {
    Optional<KnowledgeDocument> findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion(String ownerId, String taskId, int transcriptVersion);
    Optional<KnowledgeDocument> findTopByOwnerIdAndTranscriptionTaskIdOrderByUpdatedAtDesc(String ownerId, String taskId);
    List<KnowledgeDocument> findByOwnerIdOrderByUpdatedAtDesc(String ownerId);
    List<KnowledgeDocument> findTop10ByStatusOrderByCreatedAtAsc(KnowledgeDocumentStatus status);
}
