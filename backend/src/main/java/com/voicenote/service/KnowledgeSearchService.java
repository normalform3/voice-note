package com.voicenote.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.KnowledgeChunk;
import com.voicenote.domain.KnowledgeDocument;
import com.voicenote.domain.KnowledgeDocumentStatus;
import com.voicenote.provider.TextEmbeddingClient;
import com.voicenote.repository.KnowledgeChunkRepository;
import com.voicenote.repository.KnowledgeDocumentRepository;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeSearchService {
    private final TextEmbeddingClient embeddings;
    private final KnowledgeVectorStore vectors;
    private final KnowledgeChunkRepository chunks;
    private final KnowledgeDocumentRepository documents;
    private final ObjectMapper mapper;

    public KnowledgeSearchService(TextEmbeddingClient embeddings, KnowledgeVectorStore vectors, KnowledgeChunkRepository chunks, KnowledgeDocumentRepository documents, ObjectMapper mapper) {
        this.embeddings = embeddings; this.vectors = vectors; this.chunks = chunks; this.documents = documents; this.mapper = mapper;
    }

    public List<SearchHit> searchKnowledge(String ownerId, String query, int limit) {
        List<KnowledgeVectorStore.RetrievalHit> hits = vectors.search(ownerId, query, embeddings.embedQuery(query), limit);
        List<SearchHit> output = new ArrayList<>();
        for (KnowledgeVectorStore.RetrievalHit hit : hits) {
            KnowledgeChunk chunk = chunks.findById(hit.chunkId()).orElse(null);
            KnowledgeDocument document = documents.findById(hit.documentId()).orElse(null);
            if (chunk == null || document == null || !document.getOwnerId().equals(ownerId) || document.getStatus() != KnowledgeDocumentStatus.READY) continue;
            output.add(new SearchHit(document.getId(), document.getTranscriptionTaskId(), document.getTitle(), chunk.getId(), chunk.getStartMs(), chunk.getEndMs(), hit.score()));
        }
        return output;
    }

    public ReadableChunk readDocumentChunk(String ownerId, String chunkId) {
        KnowledgeChunk chunk = chunks.findById(chunkId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CHUNK_NOT_FOUND", "Knowledge chunk was not found"));
        KnowledgeDocument document = documents.findById(chunk.getKnowledgeDocumentId()).filter(value -> value.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CHUNK_NOT_FOUND", "Knowledge chunk was not found"));
        try {
            List<String> segmentIds = mapper.readValue(chunk.getSegmentIds(), new TypeReference<>() { });
            List<String> speakerIds = chunk.getSpeakerIds() == null ? List.of() : mapper.readValue(chunk.getSpeakerIds(), new TypeReference<>() { });
            List<SourceFragment> fragments = chunk.getSourceFragments() == null ? List.of() : mapper.readValue(chunk.getSourceFragments(), new TypeReference<>() { });
            return new ReadableChunk(document.getId(), document.getTranscriptionTaskId(), document.getTitle(), chunk.getId(), chunk.getTopicTitle(), chunk.getStartMs(), chunk.getEndMs(), segmentIds, speakerIds, fragments, chunk.getTextContent());
        } catch (Exception exception) { throw new IllegalStateException("Knowledge chunk contains invalid segment references", exception); }
    }

    public record SearchHit(String documentId, String transcriptionTaskId, String documentTitle, String chunkId, long startMs, long endMs, double score) { }
    public record SourceFragment(String segmentId, String speakerId, long startMs, long endMs, String text) { }
    public record ReadableChunk(String documentId, String transcriptionTaskId, String documentTitle, String chunkId, String topicTitle, long startMs, long endMs,
                                List<String> segmentIds, List<String> speakerIds, List<SourceFragment> sourceFragments, String content) {
        public ReadableChunk(String documentId, String transcriptionTaskId, String documentTitle, String chunkId, long startMs, long endMs, List<String> segmentIds, String content) {
            this(documentId, transcriptionTaskId, documentTitle, chunkId, null, startMs, endMs, segmentIds, List.of(), List.of(), content);
        }
    }
}
