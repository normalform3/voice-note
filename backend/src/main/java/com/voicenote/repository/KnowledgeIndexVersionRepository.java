package com.voicenote.repository;

import com.voicenote.domain.KnowledgeIndexVersion;
import com.voicenote.domain.KnowledgeIndexVersionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface KnowledgeIndexVersionRepository extends JpaRepository<KnowledgeIndexVersion, String> {
    List<KnowledgeIndexVersion> findByKnowledgeDocumentIdOrderByGenerationDesc(String documentId);
    Optional<KnowledgeIndexVersion> findTopByKnowledgeDocumentIdOrderByGenerationDesc(String documentId);
    List<KnowledgeIndexVersion> findTop10ByStatusOrderByUpdatedAtAsc(KnowledgeIndexVersionStatus status);
    void deleteByKnowledgeDocumentId(String documentId);
}
