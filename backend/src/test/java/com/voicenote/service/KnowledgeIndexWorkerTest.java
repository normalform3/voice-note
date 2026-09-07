package com.voicenote.service;

import com.voicenote.config.AppProperties;
import com.voicenote.domain.ChunkProfile;
import com.voicenote.domain.KnowledgeChunk;
import com.voicenote.domain.KnowledgeChunkKind;
import com.voicenote.domain.KnowledgeDocument;
import com.voicenote.domain.KnowledgeIndexVersion;
import com.voicenote.provider.TextEmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeIndexWorkerTest {
    @Test
    void suspendsTheCommittedConsumerTransactionBeforeIndexing() throws Exception {
        Transactional transactional = KnowledgeIndexWorker.class.getMethod("process", String.class)
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
    }

    @Test
    void embedsEachFinalDenseRepresentationOnceAndPersistsProviderTokenUsage() {
        AppProperties properties = new AppProperties(); properties.getWorkers().setEnabled(true);
        KnowledgeDocumentService documents = mock(KnowledgeDocumentService.class);
        KnowledgeVectorStore vectors = mock(KnowledgeVectorStore.class);
        PipelineProgressService pipeline = mock(PipelineProgressService.class);
        TextEmbeddingClient embeddings = mock(TextEmbeddingClient.class);
        KnowledgeDocument document = new KnowledgeDocument("owner", "task", 1, "Meeting");
        KnowledgeIndexVersion version = new KnowledgeIndexVersion(document.getId(), 1, "formal", 1, "hash");
        KnowledgeChunk chunk = new KnowledgeChunk(document.getId(), version.getId(), 0, 0, 1_000, "[]", "[]", "Topic", "[]", "[]", "[]",
                ChunkProfile.MEETING_DISCUSSION, KnowledgeChunkKind.NARRATIVE, 70, false, "display", "dense representation", "lexical representation", "hash");
        when(documents.claimIndex(version.getId())).thenReturn(new KnowledgeDocumentService.IndexWork(document, version, false));
        when(documents.createChunks(version.getId())).thenReturn(List.of(chunk));
        when(documents.beginIndexing(version.getId())).thenReturn(List.of(chunk));
        when(documents.topicIdsForChunks(List.of(chunk.getId()))).thenReturn(Map.of(chunk.getId(), List.of("topic")));
        when(embeddings.embedDocumentWithUsage("dense representation")).thenReturn(new TextEmbeddingClient.EmbeddedDocument(List.of(.1, .2), 1_001));
        when(documents.confirmEmbeddingUsage(version.getId(), chunk.getId(), 1_001)).thenAnswer(invocation -> { chunk.confirmEmbeddingUsage(1_001); return chunk; });
        when(documents.activate(version.getId())).thenReturn(new KnowledgeDocumentService.ActivationResult(null, true));

        new KnowledgeIndexWorker(properties, documents, vectors, pipeline, embeddings).process(version.getId());

        verify(embeddings, times(1)).embedDocumentWithUsage("dense representation");
        verify(documents).confirmEmbeddingUsage(version.getId(), chunk.getId(), 1_001);
        verify(vectors).upsert(document, version, chunk, List.of(.1, .2), List.of("topic"));
        verifyNoMoreInteractions(embeddings);
        assertThat(chunk.isOversized()).isTrue();
    }
}
