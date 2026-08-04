package com.voicenote.repository;

import com.voicenote.domain.KnowledgeRunEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KnowledgeRunEvidenceRepository extends JpaRepository<KnowledgeRunEvidence, String> {
    List<KnowledgeRunEvidence> findByKnowledgeRunId(String runId);
    List<KnowledgeRunEvidence> findByKnowledgeDocumentId(String documentId);
    void deleteByKnowledgeRunId(String runId);
}
