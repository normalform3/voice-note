package com.voicenote.repository;

import com.voicenote.domain.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, String> {
    List<KnowledgeChunk> findByKnowledgeDocumentIdOrderByChunkIndex(String documentId);
    List<KnowledgeChunk> findByKnowledgeIndexVersionIdOrderByChunkIndex(String indexVersionId);
    void deleteByKnowledgeIndexVersionId(String indexVersionId);
    void deleteByKnowledgeDocumentId(String documentId);
}
