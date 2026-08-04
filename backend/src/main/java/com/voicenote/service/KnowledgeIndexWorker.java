package com.voicenote.service;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.KnowledgeChunk;
import com.voicenote.provider.ProviderException;
import com.voicenote.provider.TextEmbeddingClient;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class KnowledgeIndexWorker {
    private final AppProperties properties;
    private final KnowledgeDocumentService documents;
    private final TextEmbeddingClient embeddings;
    private final KnowledgeVectorStore vectors;
    private final PipelineProgressService pipeline;

    public KnowledgeIndexWorker(AppProperties properties, KnowledgeDocumentService documents, TextEmbeddingClient embeddings, KnowledgeVectorStore vectors, PipelineProgressService pipeline) {
        this.properties = properties; this.documents = documents; this.embeddings = embeddings; this.vectors = vectors; this.pipeline = pipeline;
    }

    /** Invoked after the RocketMQ consumer commits the durable QUEUED transition. */
    public void process(String documentId) { if (properties.getWorkers().isEnabled()) index(documentId); }
    /** Recovery-only sweep for a process crash between consumer commit and work handoff. */
    public void recoverQueued() { if (properties.getWorkers().isEnabled()) documents.queuedDocumentIds().forEach(this::index); }

    private void index(String documentId) {
        KnowledgeDocumentService.IndexWork work = documents.claim(documentId); if (work == null) return;
        try {
            pipeline.begin(work.document().getTranscriptionTaskId(), com.voicenote.domain.PipelineStage.KNOWLEDGE_INDEX);
            vectors.ensureCollection();
            List<KnowledgeChunk> chunks = work.chunks();
            List<List<Double>> embedded = embeddings.embedDocuments(chunks.stream().map(KnowledgeChunk::getTextContent).toList());
            if (embedded.size() != chunks.size()) throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "EMBEDDING_RESPONSE_INVALID", "Embedding count does not match knowledge chunks");
            for (int index = 0; index < chunks.size(); index++) vectors.upsert(work.document(), chunks.get(index), embedded.get(index));
            if (!documents.complete(documentId)) return;
            pipeline.succeeded(work.document().getTranscriptionTaskId(), com.voicenote.domain.PipelineStage.KNOWLEDGE_INDEX,
                    "{\"documentId\":\"" + documentId + "\",\"chunkCount\":" + chunks.size() + "}", null);
        } catch (ProviderException exception) {
            documents.fail(documentId, exception.getCode() + ": " + exception.getMessage());
            pipeline.failed(work.document().getTranscriptionTaskId(), com.voicenote.domain.PipelineStage.KNOWLEDGE_INDEX, exception.getCode(), exception.getMessage(), false);
        }
        catch (RuntimeException exception) {
            documents.fail(documentId, "INDEXING_FAILED: " + exception.getMessage());
            pipeline.failed(work.document().getTranscriptionTaskId(), com.voicenote.domain.PipelineStage.KNOWLEDGE_INDEX, "INDEXING_FAILED", exception.getMessage(), false);
        }
    }
}
