package com.voicenote.service;

import com.voicenote.domain.KnowledgeChunk;
import com.voicenote.domain.KnowledgeDocument;
import com.voicenote.domain.KnowledgeIndexVersion;
import java.util.List;

public interface KnowledgeVectorStore {
    void ensureAvailable();
    void ensureCollection();
    void upsert(KnowledgeDocument document, KnowledgeIndexVersion indexVersion, KnowledgeChunk chunk, List<Double> denseVector, List<String> topicIds);
    void deleteDocument(String ownerId, String documentId);
    void deleteIndexVersion(String ownerId, String indexVersionId);
    void setVersionSearchable(String ownerId, String indexVersionId, boolean searchable);
    List<RetrievalHit> search(String ownerId, String query, List<Double> denseVector, int limit);

    record RetrievalHit(String chunkId, String documentId, String indexVersionId, long startMs, long endMs, double score) { }
}
