package com.voicenote.repository;

import com.voicenote.domain.KnowledgeRunDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KnowledgeRunDocumentRepository extends JpaRepository<KnowledgeRunDocument, String> {
    List<KnowledgeRunDocument> findByKnowledgeRunIdOrderByCreatedAtAsc(String runId);
    List<KnowledgeRunDocument> findByTranscriptionTaskId(String taskId);
    void deleteByKnowledgeRunId(String runId);
}
