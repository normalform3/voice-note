package com.voicenote.service;

import com.voicenote.domain.KnowledgeChunk;
import com.voicenote.domain.KnowledgeDocument;
import java.util.List;

public interface KnowledgeVectorStore {
    void ensureCollection();
    void upsert(KnowledgeDocument document, KnowledgeChunk chunk, List<Double> denseVector);
    List<RetrievalHit> search(String ownerId, String query, List<Double> denseVector, int limit);

    record RetrievalHit(String chunkId, String documentId, long startMs, long endMs, double score) { }
}
