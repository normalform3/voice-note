package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.KnowledgeChunk;
import com.voicenote.domain.ChunkProfile;
import com.voicenote.domain.KnowledgeChunkKind;
import com.voicenote.domain.KnowledgeDocument;
import com.voicenote.provider.TextEmbeddingClient;
import com.voicenote.provider.TextRerankClient;
import com.voicenote.repository.KnowledgeChunkRepository;
import com.voicenote.repository.KnowledgeChunkTopicRepository;
import com.voicenote.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeSearchCoverageTest {
    @Test
    void reservesOneResultForEveryDocumentWithAHybridHit() {
        TextEmbeddingClient embeddings = mock(TextEmbeddingClient.class);
        KnowledgeVectorStore vectors = mock(KnowledgeVectorStore.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        when(embeddings.embedQuery("compare")).thenReturn(List.of(0.1, 0.2));
        Map<String, KnowledgeChunk> storedChunks = new HashMap<>();
        List<KnowledgeSearchService.ScopedDocument> scope = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            KnowledgeDocument document = new KnowledgeDocument("owner", "task-" + index, 1, "Document " + index);
            String version = "version-" + index;
            KnowledgeChunk chunk = new KnowledgeChunk(document.getId(), version, 0, index * 1_000L, index * 1_000L + 900,
                    "[]", "[]", "Topic", "[]", "[]", "[]", 20, false, "content " + index, "hash-" + index);
            storedChunks.put(chunk.getId(), chunk); scope.add(new KnowledgeSearchService.ScopedDocument("task-" + index, document.getId(), version));
            when(chunks.findById(chunk.getId())).thenReturn(Optional.of(chunk));
            when(vectors.searchScoped("owner", document.getId(), version, "compare", List.of(0.1, 0.2), 8))
                    .thenReturn(List.of(new KnowledgeVectorStore.RetrievalHit(chunk.getId(), document.getId(), version, chunk.getStartMs(), chunk.getEndMs(), 1 - index * .1)));
            when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        }
        when(chunks.findAllById(any())).thenAnswer(invocation -> {
            List<KnowledgeChunk> output = new ArrayList<>();
            Iterable<String> ids = invocation.getArgument(0);
            for (String id : ids) if (storedChunks.containsKey(id)) output.add(storedChunks.get(id));
            return output;
        });
        TextRerankClient reranker = (query, candidates) -> new TextRerankClient.RerankResult(
                candidates.stream().map(value -> new TextRerankClient.Ranked(value.id(), value.retrievalScore())).toList(), true, "rerankUnavailable");
        KnowledgeSearchService service = new KnowledgeSearchService(embeddings, vectors, chunks, documents,
                mock(KnowledgeChunkTopicRepository.class), new AppProperties(), new ObjectMapper(), reranker);

        var result = service.searchScoped("owner", scope, "compare", 2);

        assertThat(result.coveredDocumentIds()).containsExactlyInAnyOrder("task-0", "task-1", "task-2");
        assertThat(result.chunks()).hasSize(3);
        assertThat(result.rerankFallback()).isTrue();
        assertThat(result.limitation()).isEqualTo("rerankUnavailable");
    }

    @Test
    void enforcesTheTokenBudgetWithoutReturningPartialChunks() {
        TextEmbeddingClient embeddings = mock(TextEmbeddingClient.class);
        KnowledgeVectorStore vectors = mock(KnowledgeVectorStore.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        when(embeddings.embedQuery("compare")).thenReturn(List.of(0.1));
        AppProperties properties = new AppProperties(); properties.getKnowledge().setRetrievalContextMaxTokens(100);
        List<KnowledgeSearchService.ScopedDocument> scope = new ArrayList<>(); Map<String, KnowledgeChunk> stored = new HashMap<>();
        for (int index = 0; index < 2; index++) {
            KnowledgeDocument document = new KnowledgeDocument("owner", "task-" + index, 1, "Document " + index);
            String version = "version-" + index;
            KnowledgeChunk chunk = new KnowledgeChunk(document.getId(), version, 0, 0, 1_000, "[]", "[]", "Topic", "[]", "[]", "[]",
                    ChunkProfile.INTERVIEW_QA, KnowledgeChunkKind.QA, 60, false, "complete-" + index, "dense-" + index, "lexical-" + index, "hash-" + index);
            scope.add(new KnowledgeSearchService.ScopedDocument("task-" + index, document.getId(), version)); stored.put(chunk.getId(), chunk);
            when(vectors.searchScoped("owner", document.getId(), version, "compare", List.of(0.1), 8)).thenReturn(List.of(
                    new KnowledgeVectorStore.RetrievalHit(chunk.getId(), document.getId(), version, 0, 1_000, 1 - index * .1)));
            when(chunks.findById(chunk.getId())).thenReturn(Optional.of(chunk)); when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        }
        when(chunks.findAllById(any())).thenAnswer(invocation -> {
            List<KnowledgeChunk> output = new ArrayList<>(); Iterable<String> ids = invocation.getArgument(0);
            for (String id : ids) if (stored.containsKey(id)) output.add(stored.get(id)); return output;
        });
        TextRerankClient reranker = (query, candidates) -> new TextRerankClient.RerankResult(
                candidates.stream().map(value -> new TextRerankClient.Ranked(value.id(), value.retrievalScore())).toList(), false, null);
        KnowledgeSearchService service = new KnowledgeSearchService(embeddings, vectors, chunks, documents,
                mock(KnowledgeChunkTopicRepository.class), properties, new ObjectMapper(), reranker);

        var result = service.searchScoped("owner", scope, "compare", 1);

        assertThat(result.chunks()).singleElement().extracting(KnowledgeSearchService.ReadableChunk::content).isEqualTo("complete-0");
        assertThat(result.uncoveredDocumentIds()).containsExactly("task-1");
        assertThat(result.truncationReason()).isEqualTo("contextTokenLimit");
    }
}
