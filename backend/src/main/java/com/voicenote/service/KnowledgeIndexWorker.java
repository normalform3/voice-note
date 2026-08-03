package com.voicenote.service;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.KnowledgeChunk;
import com.voicenote.provider.ProviderException;
import com.voicenote.provider.TextEmbeddingClient;
import org.springframework.scheduling.annotation.Scheduled;
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

    @Scheduled(fixedDelayString = "${app.workers.poll-interval-ms:5000}")
    public void work() { if (properties.getWorkers().isEnabled()) documents.queuedDocumentIds().forEach(this::index); }

    private void index(String documentId) {
        KnowledgeDocumentService.IndexWork work = documents.claim(documentId); if (work == null) return;
        try {
            pipeline.begin(work.document().getTranscriptionTaskId(), com.voicenote.domain.PipelineStage.KNOWLEDGE_INDEX);
            vectors.ensureCollection();
            List<KnowledgeChunk> chunks = work.chunks();
            List<List<Double>> embedded = embeddings.embedDocuments(chunks.stream().map(KnowledgeChunk::getTextContent).toList());
            if (embedded.size() != chunks.size()) throw new ProviderException(ProviderException.Kind.FINAL_REJECTION, "EMBEDDING_RESPONSE_INVALID", "Embedding count does not match knowledge chunks");
            for (int index = 0; index < chunks.size(); index++) vectors.upsert(work.document(), chunks.get(index), embedded.get(index));
            documents.complete(documentId);
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
