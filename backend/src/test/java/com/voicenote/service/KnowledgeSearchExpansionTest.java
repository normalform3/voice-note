package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.ChunkProfile;
import com.voicenote.domain.KnowledgeChunk;
import com.voicenote.domain.KnowledgeChunkKind;
import com.voicenote.domain.KnowledgeChunkTopic;
import com.voicenote.domain.KnowledgeDocument;
import com.voicenote.provider.TextEmbeddingClient;
import com.voicenote.provider.TextRerankClient;
import com.voicenote.repository.KnowledgeChunkRepository;
import com.voicenote.repository.KnowledgeChunkTopicRepository;
import com.voicenote.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KnowledgeSearchExpansionTest {
    @Test
    void reranksDenseTextThenExpandsOnlyWithinTheSameTopicAndGeneration() {
        TextEmbeddingClient embeddings = mock(TextEmbeddingClient.class);
        KnowledgeVectorStore vectors = mock(KnowledgeVectorStore.class);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeChunkTopicRepository links = mock(KnowledgeChunkTopicRepository.class);
        AppProperties properties = new AppProperties();
        properties.getKnowledge().setRetrievalCandidatePerDocument(8);
        when(embeddings.embedQuery("decision")).thenReturn(List.of(0.1, 0.2));

        KnowledgeDocument document = new KnowledgeDocument("owner", "task", 1, "Meeting");
        String version = "version-current";
        KnowledgeChunk previous = chunk(document.getId(), version, 0, "previous", ChunkProfile.MEETING_DISCUSSION, KnowledgeChunkKind.NARRATIVE);
        KnowledgeChunk seed = chunk(document.getId(), version, 1, "display seed", ChunkProfile.MEETING_DISCUSSION, KnowledgeChunkKind.NARRATIVE,
                "dense metadata and seed", "lexical raw seed");
        KnowledgeChunk wrongGeneration = chunk(document.getId(), "version-old", 2, "old", ChunkProfile.MEETING_DISCUSSION, KnowledgeChunkKind.NARRATIVE);
        Map<String, KnowledgeChunk> stored = Map.of(previous.getId(), previous, seed.getId(), seed, wrongGeneration.getId(), wrongGeneration);

        when(vectors.searchScoped("owner", document.getId(), version, "decision", List.of(0.1, 0.2), 8)).thenReturn(List.of(
                new KnowledgeVectorStore.RetrievalHit(seed.getId(), document.getId(), version, 0, 1_000, .7)));
        when(chunks.findAllById(any())).thenAnswer(invocation -> {
            List<KnowledgeChunk> output = new ArrayList<>();
            Iterable<String> ids = invocation.getArgument(0);
            for (String id : ids) if (stored.containsKey(id)) output.add(stored.get(id));
            return output;
        });
        stored.values().forEach(value -> when(chunks.findById(value.getId())).thenReturn(Optional.of(value)));
        when(documents.findById(document.getId())).thenReturn(Optional.of(document));
        KnowledgeChunkTopic previousLink = new KnowledgeChunkTopic(previous.getId(), "topic", 0, 0);
        KnowledgeChunkTopic seedLink = new KnowledgeChunkTopic(seed.getId(), "topic", 0, 1);
        KnowledgeChunkTopic wrongLink = new KnowledgeChunkTopic(wrongGeneration.getId(), "topic", 0, 2);
        when(links.findByKnowledgeChunkIdIn(any())).thenReturn(List.of(seedLink));
        when(links.findByKnowledgeTopicIdIn(any())).thenReturn(List.of(previousLink, seedLink, wrongLink));

        AtomicReference<List<TextRerankClient.Candidate>> rerankCandidates = new AtomicReference<>();
        TextRerankClient reranker = (query, candidates) -> {
            rerankCandidates.set(candidates);
            return new TextRerankClient.RerankResult(List.of(new TextRerankClient.Ranked(seed.getId(), .9)), false, null);
        };
        KnowledgeSearchService service = new KnowledgeSearchService(embeddings, vectors, chunks, documents, links, properties, new ObjectMapper(), reranker);

        var result = service.searchScoped("owner", List.of(new KnowledgeSearchService.ScopedDocument("task", document.getId(), version)), "decision", 1);

        assertThat(rerankCandidates.get()).singleElement().extracting(TextRerankClient.Candidate::text).isEqualTo("dense metadata and seed");
        assertThat(result.chunks()).extracting(KnowledgeSearchService.ReadableChunk::chunkId).containsExactly(seed.getId(), previous.getId());
        assertThat(result.chunks()).extracting(KnowledgeSearchService.ReadableChunk::chunkId).doesNotContain(wrongGeneration.getId());
    }

    @Test
    void doesNotExpandQaOrAnswerParts() {
        KnowledgeChunk qa = chunk("document", "version", 0, "qa", ChunkProfile.INTERVIEW_QA, KnowledgeChunkKind.QA);
        KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
        KnowledgeChunkTopicRepository links = mock(KnowledgeChunkTopicRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeVectorStore vectors = mock(KnowledgeVectorStore.class);
        TextEmbeddingClient embeddings = mock(TextEmbeddingClient.class);
        when(embeddings.embedQuery("question")).thenReturn(List.of(.1));
        when(vectors.searchScoped("owner", "document", "version", "question", List.of(.1), 8)).thenReturn(List.of(
                new KnowledgeVectorStore.RetrievalHit(qa.getId(), "document", "version", 0, 1_000, 1)));
        when(chunks.findAllById(any())).thenReturn(List.of(qa));
        when(chunks.findById(qa.getId())).thenReturn(Optional.of(qa));
        when(documents.findById("document")).thenReturn(Optional.of(new KnowledgeDocument("owner", "task", 1, "Interview")));
        TextRerankClient reranker = (query, candidates) -> new TextRerankClient.RerankResult(
                List.of(new TextRerankClient.Ranked(qa.getId(), 1)), false, null);
        KnowledgeSearchService service = new KnowledgeSearchService(embeddings, vectors, chunks, documents, links, new AppProperties(), new ObjectMapper(), reranker);

        var result = service.searchScoped("owner", List.of(new KnowledgeSearchService.ScopedDocument("task", "document", "version")), "question", 1);

        assertThat(result.chunks()).singleElement().extracting(KnowledgeSearchService.ReadableChunk::chunkId).isEqualTo(qa.getId());
        verifyNoInteractions(links);
    }

    private static KnowledgeChunk chunk(String documentId, String version, int index, String content, ChunkProfile profile, KnowledgeChunkKind kind) {
        return chunk(documentId, version, index, content, profile, kind, content, content);
    }
    private static KnowledgeChunk chunk(String documentId, String version, int index, String content, ChunkProfile profile, KnowledgeChunkKind kind,
                                         String dense, String lexical) {
        return new KnowledgeChunk(documentId, version, index, index * 1_000L, index * 1_000L + 900, "[]", "[]", "Topic", "[]", "[]", "[]",
                profile, kind, 50, false, content, dense, lexical, "hash-" + index + "-" + version);
    }
}
