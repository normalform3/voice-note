package com.voicenote.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.KnowledgeChunk;
import com.voicenote.domain.KnowledgeDocument;
import com.voicenote.domain.KnowledgeDocumentStatus;
import com.voicenote.domain.KnowledgeChunkTopic;
import com.voicenote.config.AppProperties;
import com.voicenote.provider.TextEmbeddingClient;
import com.voicenote.repository.KnowledgeChunkRepository;
import com.voicenote.repository.KnowledgeChunkTopicRepository;
import com.voicenote.repository.KnowledgeDocumentRepository;
import com.voicenote.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class KnowledgeSearchService {
    private final TextEmbeddingClient embeddings;
    private final KnowledgeVectorStore vectors;
    private final KnowledgeChunkRepository chunks;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeChunkTopicRepository chunkTopics;
    private final AppProperties properties;
    private final ObjectMapper mapper;

    public KnowledgeSearchService(TextEmbeddingClient embeddings, KnowledgeVectorStore vectors, KnowledgeChunkRepository chunks, KnowledgeDocumentRepository documents,
                                  KnowledgeChunkTopicRepository chunkTopics, AppProperties properties, ObjectMapper mapper) {
        this.embeddings = embeddings; this.vectors = vectors; this.chunks = chunks; this.documents = documents; this.chunkTopics = chunkTopics; this.properties = properties; this.mapper = mapper;
    }

    public List<SearchHit> searchKnowledge(String ownerId, String query, int limit) {
        int resultLimit = Math.max(limit, 20);
        List<KnowledgeVectorStore.RetrievalHit> hits = vectors.search(ownerId, query, embeddings.embedQuery(query), resultLimit);
        Map<String, KnowledgeChunk> storedChunks = new LinkedHashMap<>();
        chunks.findAllById(hits.stream().map(KnowledgeVectorStore.RetrievalHit::chunkId).toList()).forEach(value -> storedChunks.put(value.getId(), value));
        Map<String, KnowledgeDocument> storedDocuments = new LinkedHashMap<>();
        documents.findAllById(hits.stream().map(KnowledgeVectorStore.RetrievalHit::documentId).toList()).forEach(value -> storedDocuments.put(value.getId(), value));
        List<SearchHit> output = new ArrayList<>();
        for (KnowledgeVectorStore.RetrievalHit hit : hits) {
            KnowledgeChunk chunk = storedChunks.get(hit.chunkId()); KnowledgeDocument document = storedDocuments.get(hit.documentId());
            if (chunk == null || document == null || !document.getOwnerId().equals(ownerId) || document.getStatus() != KnowledgeDocumentStatus.READY
                    || !Objects.equals(document.getActiveIndexVersionId(), hit.indexVersionId()) || !Objects.equals(chunk.getKnowledgeIndexVersionId(), hit.indexVersionId())) continue;
            output.add(new SearchHit(document.getId(), document.getTranscriptionTaskId(), document.getTitle(), hit.indexVersionId(), chunk.getId(), chunk.getStartMs(), chunk.getEndMs(), hit.score()));
        }
        return output;
    }

    public ReadableChunk readDocumentChunk(String ownerId, String chunkId) {
        KnowledgeChunk chunk = chunks.findById(chunkId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CHUNK_NOT_FOUND", "Knowledge chunk was not found"));
        KnowledgeDocument document = documents.findById(chunk.getKnowledgeDocumentId()).filter(value -> value.getOwnerId().equals(ownerId) && value.getStatus() == KnowledgeDocumentStatus.READY
                        && Objects.equals(value.getActiveIndexVersionId(), chunk.getKnowledgeIndexVersionId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CHUNK_NOT_FOUND", "Knowledge chunk was not found"));
        try {
            List<String> segmentIds = mapper.readValue(chunk.getSegmentIds(), new TypeReference<>() { });
            List<String> speakerIds = chunk.getSpeakerIds() == null ? List.of() : mapper.readValue(chunk.getSpeakerIds(), new TypeReference<>() { });
            List<SourceFragment> fragments = chunk.getSourceFragments() == null ? List.of() : mapper.readValue(chunk.getSourceFragments(), new TypeReference<>() { });
            return new ReadableChunk(document.getId(), document.getTranscriptionTaskId(), document.getTitle(), chunk.getId(), chunk.getTopicTitle(), chunk.getStartMs(), chunk.getEndMs(), segmentIds, speakerIds, fragments, chunk.getTextContent());
        } catch (Exception exception) { throw new IllegalStateException("Knowledge chunk contains invalid segment references", exception); }
    }

    /** Expands each seed by one neighbouring chunk within every Topic it belongs to, never across Topic boundaries. */
    public List<ReadableChunk> readExpandedContext(String ownerId, List<SearchHit> hits) {
        List<SearchHit> seeds = hits.stream().limit(properties.getKnowledge().getRetrievalSeedLimit()).toList();
        if (seeds.isEmpty()) return List.of();
        Map<String, KnowledgeChunk> known = new LinkedHashMap<>();
        chunks.findAllById(seeds.stream().map(SearchHit::chunkId).toList()).forEach(value -> known.put(value.getId(), value));
        List<KnowledgeChunkTopic> seedLinks = chunkTopics.findByKnowledgeChunkIdIn(known.keySet());
        Set<String> topicIds = seedLinks.stream().map(KnowledgeChunkTopic::getKnowledgeTopicId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, List<KnowledgeChunkTopic>> linksByTopic = new HashMap<>();
        for (KnowledgeChunkTopic link : chunkTopics.findByKnowledgeTopicIdIn(topicIds)) linksByTopic.computeIfAbsent(link.getKnowledgeTopicId(), ignored -> new ArrayList<>()).add(link);
        Set<String> selectedIds = new LinkedHashSet<>(known.keySet());
        for (KnowledgeChunkTopic seed : seedLinks) {
            for (KnowledgeChunkTopic candidate : linksByTopic.getOrDefault(seed.getKnowledgeTopicId(), List.of())) {
                if (Math.abs(candidate.getChunkIndexInTopic() - seed.getChunkIndexInTopic()) <= 1) selectedIds.add(candidate.getKnowledgeChunkId());
            }
        }
        chunks.findAllById(selectedIds).forEach(value -> known.put(value.getId(), value));
        List<KnowledgeChunk> ordered = known.values().stream().filter(value -> selectedIds.contains(value.getId()))
                .sorted(java.util.Comparator.comparing(KnowledgeChunk::getKnowledgeDocumentId).thenComparingInt(KnowledgeChunk::getChunkIndex)).toList();
        List<ReadableChunk> output = new ArrayList<>(); int tokens = 0;
        for (KnowledgeChunk chunk : ordered) {
            if (output.size() >= properties.getKnowledge().getRetrievalContextMaxChunks()) break;
            int next = chunk.getTokenCount() == null ? 0 : chunk.getTokenCount();
            if (!output.isEmpty() && tokens + next > properties.getKnowledge().getRetrievalContextMaxTokens()) break;
            output.add(readDocumentChunk(ownerId, chunk.getId())); tokens += next;
        }
        return output;
    }

    public record SearchHit(String documentId, String transcriptionTaskId, String documentTitle, String indexVersionId, String chunkId, long startMs, long endMs, double score) { }
    public record SourceFragment(String segmentId, String speakerId, long startMs, long endMs, String text) { }
    public record ReadableChunk(String documentId, String transcriptionTaskId, String documentTitle, String chunkId, String topicTitle, long startMs, long endMs,
                                List<String> segmentIds, List<String> speakerIds, List<SourceFragment> sourceFragments, String content) {
        public ReadableChunk(String documentId, String transcriptionTaskId, String documentTitle, String chunkId, long startMs, long endMs, List<String> segmentIds, String content) {
            this(documentId, transcriptionTaskId, documentTitle, chunkId, null, startMs, endMs, segmentIds, List.of(), List.of(), content);
        }
    }
}
