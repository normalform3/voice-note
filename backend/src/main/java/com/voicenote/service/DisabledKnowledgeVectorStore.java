package com.voicenote.service;

import com.voicenote.domain.KnowledgeChunk;
import com.voicenote.domain.KnowledgeDocument;
import com.voicenote.provider.ProviderException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@ConditionalOnProperty(name = "app.knowledge.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledKnowledgeVectorStore implements KnowledgeVectorStore {
    private ProviderException disabled() { return new ProviderException(ProviderException.Kind.FINAL_REJECTION, "KNOWLEDGE_DISABLED", "Knowledge indexing is disabled; configure VOICENOTE_KNOWLEDGE_ENABLED and Qdrant"); }
    @Override public void ensureCollection() { throw disabled(); }
    @Override public void upsert(KnowledgeDocument document, KnowledgeChunk chunk, List<Double> denseVector) { throw disabled(); }
    @Override public void deleteDocument(String ownerId, String documentId) { }
    @Override public List<RetrievalHit> search(String ownerId, String query, List<Double> denseVector, int limit) { throw disabled(); }
}
