package com.voicenote.service;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.KnowledgeChunk;
import com.voicenote.domain.PipelineStage;
import com.voicenote.provider.ProviderException;
import com.voicenote.provider.TextEmbeddingClient;
import com.voicenote.provider.TextEmbeddingClient.EmbeddedDocument;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Component
public class KnowledgeIndexWorker {
    private final AppProperties properties;
    private final KnowledgeDocumentService documents;
    private final KnowledgeVectorStore vectors;
    private final PipelineProgressService pipeline;
    private final TextEmbeddingClient embeddings;

    public KnowledgeIndexWorker(AppProperties properties, KnowledgeDocumentService documents, KnowledgeVectorStore vectors, PipelineProgressService pipeline, TextEmbeddingClient embeddings) {
        this.properties = properties; this.documents = documents; this.vectors = vectors; this.pipeline = pipeline; this.embeddings = embeddings;
    }

    /** Invoked after the consumer commits the durable queued transition for an index version. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void process(String indexVersionId) { if (properties.getWorkers().isEnabled()) index(indexVersionId); }
    public void recoverQueued() { if (properties.getWorkers().isEnabled()) documents.queuedIndexVersionIds().forEach(this::index); }

    private void index(String indexVersionId) {
        KnowledgeDocumentService.IndexWork work = documents.claimIndex(indexVersionId); if (work == null) return;
        boolean initialBuild = work.bridgeTaskPipeline();
        try {
            if (initialBuild) pipeline.begin(work.document().getTranscriptionTaskId(), PipelineStage.KNOWLEDGE_INDEX);
            vectors.ensureAvailable(); vectors.ensureCollection();
            documents.ingestTopics(indexVersionId);
            if (initialBuild) pipeline.renewLease(work.document().getTranscriptionTaskId(), PipelineStage.KNOWLEDGE_INDEX);
            documents.createChunks(indexVersionId);
            if (initialBuild) pipeline.renewLease(work.document().getTranscriptionTaskId(), PipelineStage.KNOWLEDGE_INDEX);
            List<KnowledgeChunk> chunks = documents.beginIndexing(indexVersionId);
            Map<String, List<String>> topicIds = documents.topicIdsForChunks(chunks.stream().map(KnowledgeChunk::getId).toList());
            vectors.deleteIndexVersion(work.document().getOwnerId(), indexVersionId);
            for (int index = 0; index < chunks.size(); index++) {
                KnowledgeChunk chunk = chunks.get(index);
                if (properties.getDashscope().isEnabled()) pipeline.recordModelInvocation(work.document().getTranscriptionTaskId(), PipelineStage.KNOWLEDGE_INDEX, properties.getDashscope().getEmbeddingModel());
                EmbeddedDocument embedded = embeddings.embedDocumentWithUsage(chunk.getDenseText());
                int actualTokens = embedded.promptTokens() == null ? Objects.requireNonNullElse(chunk.getTokenCount(), 0) : embedded.promptTokens();
                chunk = documents.confirmEmbeddingUsage(indexVersionId, chunk.getId(), actualTokens);
                vectors.upsert(work.document(), work.indexVersion(), chunk, embedded.vector(), topicIds.getOrDefault(chunk.getId(), List.of()));
                if ((index + 1) % 10 == 0 || index + 1 == chunks.size()) {
                    if (initialBuild) pipeline.renewLease(work.document().getTranscriptionTaskId(), PipelineStage.KNOWLEDGE_INDEX);
                    documents.indexedProgress(indexVersionId, index + 1, chunks.size());
                }
            }
            vectors.setVersionSearchable(work.document().getOwnerId(), indexVersionId, true);
            KnowledgeDocumentService.ActivationResult activation = documents.activate(indexVersionId);
            if (!activation.activated()) vectors.setVersionSearchable(work.document().getOwnerId(), indexVersionId, false);
            else if (activation.previousIndexVersionId() != null && !activation.previousIndexVersionId().equals(indexVersionId)) {
                vectors.setVersionSearchable(work.document().getOwnerId(), activation.previousIndexVersionId(), false);
            }
            if (initialBuild) pipeline.succeeded(work.document().getTranscriptionTaskId(), PipelineStage.KNOWLEDGE_INDEX,
                    "{\"documentId\":\"" + work.document().getId() + "\",\"indexVersionId\":\"" + indexVersionId + "\",\"chunkCount\":" + chunks.size() + "}", null);
        } catch (ProviderException exception) {
            documents.failIndex(indexVersionId, exception.getCode(), exception.getMessage());
            if (initialBuild) pipeline.failed(work.document().getTranscriptionTaskId(), PipelineStage.KNOWLEDGE_INDEX, exception.getCode(), exception.getMessage(), false);
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            documents.failIndex(indexVersionId, "INDEXING_FAILED", message);
            if (initialBuild) pipeline.failed(work.document().getTranscriptionTaskId(), PipelineStage.KNOWLEDGE_INDEX, "INDEXING_FAILED", message, false);
        }
    }
}
