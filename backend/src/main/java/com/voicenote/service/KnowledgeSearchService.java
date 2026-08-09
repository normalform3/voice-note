package com.voicenote.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.provider.TextEmbeddingClient;
import com.voicenote.provider.TextRerankClient;
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
    private final TextRerankClient reranker;

    public KnowledgeSearchService(TextEmbeddingClient embeddings, KnowledgeVectorStore vectors, KnowledgeChunkRepository chunks, KnowledgeDocumentRepository documents,
                                  KnowledgeChunkTopicRepository chunkTopics, AppProperties properties, ObjectMapper mapper, TextRerankClient reranker) {
        this.embeddings = embeddings; this.vectors = vectors; this.chunks = chunks; this.documents = documents; this.chunkTopics = chunkTopics;
        this.properties = properties; this.mapper = mapper; this.reranker = reranker;
    }

    /** Legacy owner-wide search retained for /knowledge-runs compatibility. */
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
        KnowledgeChunk chunk = chunks.findById(chunkId).orElseThrow(() -> notFound());
        KnowledgeDocument document = documents.findById(chunk.getKnowledgeDocumentId()).filter(value -> value.getOwnerId().equals(ownerId) && value.getStatus() == KnowledgeDocumentStatus.READY
                        && Objects.equals(value.getActiveIndexVersionId(), chunk.getKnowledgeIndexVersionId()))
                .orElseThrow(KnowledgeSearchService::notFound);
        return readable(document, chunk);
    }

    /** Reads a chunk from the exact index generation captured by an Agent Run, including a generation retired after the run started. */
    public ReadableChunk readScopedDocumentChunk(String ownerId, String chunkId, String expectedIndexVersionId) {
        KnowledgeChunk chunk = chunks.findById(chunkId).filter(value -> Objects.equals(value.getKnowledgeIndexVersionId(), expectedIndexVersionId)).orElseThrow(() -> notFound());
        KnowledgeDocument document = documents.findById(chunk.getKnowledgeDocumentId()).filter(value -> value.getOwnerId().equals(ownerId)).orElseThrow(KnowledgeSearchService::notFound);
        return readable(document, chunk);
    }

    public ScopedSearchResult searchScoped(String ownerId, List<ScopedDocument> scope, String query, int perDocumentLimit) {
        if (scope.isEmpty()) return new ScopedSearchResult(List.of(), List.of(), List.of(), true, "noIndexedDocuments", "noIndexedDocuments");
        if (scope.size() > 12) throw new ApiException(HttpStatus.BAD_REQUEST, "SEARCH_SCOPE_TOO_LARGE", "One knowledge search may target at most 12 documents; use document overviews first");
        int quota = Math.max(1, Math.min(perDocumentLimit, 4));
        List<Double> queryVector = embeddings.embedQuery(query);
        Map<String, ScopedDocument> scopeByDocument = new LinkedHashMap<>();
        Map<String, KnowledgeChunk> candidateChunks = new LinkedHashMap<>();
        Map<String, Double> retrievalScores = new HashMap<>();
        for (ScopedDocument document : scope) {
            scopeByDocument.put(document.documentId(), document);
            List<KnowledgeVectorStore.RetrievalHit> hits = vectors.searchScoped(ownerId, document.documentId(), document.indexVersionId(), query, queryVector, 4);
            Map<String, KnowledgeChunk> stored = new HashMap<>();
            chunks.findAllById(hits.stream().map(KnowledgeVectorStore.RetrievalHit::chunkId).toList()).forEach(value -> stored.put(value.getId(), value));
            for (KnowledgeVectorStore.RetrievalHit hit : hits) {
                KnowledgeChunk chunk = stored.get(hit.chunkId());
                if (chunk == null || !Objects.equals(chunk.getKnowledgeDocumentId(), document.documentId()) || !Objects.equals(chunk.getKnowledgeIndexVersionId(), document.indexVersionId())) continue;
                candidateChunks.putIfAbsent(chunk.getId(), chunk); retrievalScores.put(chunk.getId(), hit.score());
            }
        }
        List<KnowledgeChunk> pool = candidateChunks.values().stream().limit(50).toList();
        TextRerankClient.RerankResult reranked = reranker.rerank(query, pool.stream().map(chunk ->
                new TextRerankClient.Candidate(chunk.getId(), chunk.getTextContent(), retrievalScores.getOrDefault(chunk.getId(), 0d))).toList());
        Map<String, Integer> rank = new HashMap<>();
        for (int index = 0; index < reranked.ranked().size(); index++) rank.put(reranked.ranked().get(index).id(), index);
        List<KnowledgeChunk> ordered = pool.stream().sorted(Comparator.comparingInt(value -> rank.getOrDefault(value.getId(), Integer.MAX_VALUE))).toList();
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (ScopedDocument requested : scope) ordered.stream().filter(value -> value.getKnowledgeDocumentId().equals(requested.documentId())).findFirst().ifPresent(value -> selected.add(value.getId()));
        Map<String, Integer> perDocument = new HashMap<>();
        for (String id : selected) perDocument.merge(candidateChunks.get(id).getKnowledgeDocumentId(), 1, Integer::sum);
        for (KnowledgeChunk chunk : ordered) {
            if (selected.size() >= properties.getKnowledge().getRetrievalContextMaxChunks()) break;
            if (selected.contains(chunk.getId()) || perDocument.getOrDefault(chunk.getKnowledgeDocumentId(), 0) >= quota) continue;
            selected.add(chunk.getId()); perDocument.merge(chunk.getKnowledgeDocumentId(), 1, Integer::sum);
        }
        List<ReadableChunk> readable = new ArrayList<>(); int tokens = 0; boolean tokenTruncated = false;
        for (String id : selected) {
            KnowledgeChunk chunk = candidateChunks.get(id); int next = chunk.getTokenCount() == null ? 0 : chunk.getTokenCount();
            if (!readable.isEmpty() && tokens + next > properties.getKnowledge().getRetrievalContextMaxTokens()) { tokenTruncated = true; break; }
            ScopedDocument scoped = scopeByDocument.get(chunk.getKnowledgeDocumentId());
            readable.add(readScopedDocumentChunk(ownerId, id, scoped.indexVersionId())); tokens += next;
        }
        List<String> covered = readable.stream().map(ReadableChunk::transcriptionTaskId).distinct().toList();
        List<String> uncovered = scope.stream().map(ScopedDocument::taskId).filter(taskId -> !covered.contains(taskId)).toList();
        boolean chunkTruncated = selected.size() >= properties.getKnowledge().getRetrievalContextMaxChunks() && ordered.size() > selected.size();
        String truncationReason = tokenTruncated ? "contextTokenLimit" : chunkTruncated ? "contextChunkLimit" : null;
        return new ScopedSearchResult(List.copyOf(readable), covered, uncovered, reranked.fallback(), reranked.limitation(), truncationReason);
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
        for (KnowledgeChunkTopic seed : seedLinks) for (KnowledgeChunkTopic candidate : linksByTopic.getOrDefault(seed.getKnowledgeTopicId(), List.of())) {
            if (Math.abs(candidate.getChunkIndexInTopic() - seed.getChunkIndexInTopic()) <= 1) selectedIds.add(candidate.getKnowledgeChunkId());
        }
        chunks.findAllById(selectedIds).forEach(value -> known.put(value.getId(), value));
        List<KnowledgeChunk> ordered = known.values().stream().filter(value -> selectedIds.contains(value.getId()))
                .sorted(Comparator.comparing(KnowledgeChunk::getKnowledgeDocumentId).thenComparingInt(KnowledgeChunk::getChunkIndex)).toList();
        List<ReadableChunk> output = new ArrayList<>(); int tokens = 0;
        for (KnowledgeChunk chunk : ordered) {
            if (output.size() >= properties.getKnowledge().getRetrievalContextMaxChunks()) break;
            int next = chunk.getTokenCount() == null ? 0 : chunk.getTokenCount();
            if (!output.isEmpty() && tokens + next > properties.getKnowledge().getRetrievalContextMaxTokens()) break;
            output.add(readDocumentChunk(ownerId, chunk.getId())); tokens += next;
        }
        return output;
    }

    private ReadableChunk readable(KnowledgeDocument document, KnowledgeChunk chunk) {
        try {
            List<String> segmentIds = mapper.readValue(chunk.getSegmentIds(), new TypeReference<>() { });
            List<String> speakerIds = chunk.getSpeakerIds() == null ? List.of() : mapper.readValue(chunk.getSpeakerIds(), new TypeReference<>() { });
            List<SourceFragment> fragments = chunk.getSourceFragments() == null ? List.of() : mapper.readValue(chunk.getSourceFragments(), new TypeReference<>() { });
            return new ReadableChunk(document.getId(), document.getTranscriptionTaskId(), document.getTitle(), chunk.getId(), chunk.getTopicTitle(), chunk.getStartMs(), chunk.getEndMs(), segmentIds, speakerIds, fragments, chunk.getTextContent());
        } catch (Exception exception) { throw new IllegalStateException("Knowledge chunk contains invalid segment references", exception); }
    }

    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "CHUNK_NOT_FOUND", "Knowledge chunk was not found"); }

    public record SearchHit(String documentId, String transcriptionTaskId, String documentTitle, String indexVersionId, String chunkId, long startMs, long endMs, double score) { }
    public record ScopedDocument(String taskId, String documentId, String indexVersionId) { }
    public record ScopedSearchResult(List<ReadableChunk> chunks, List<String> coveredDocumentIds, List<String> uncoveredDocumentIds,
                                     boolean rerankFallback, String limitation, String truncationReason) { }
    public record SourceFragment(String segmentId, String speakerId, long startMs, long endMs, String text) { }
    public record ReadableChunk(String documentId, String transcriptionTaskId, String documentTitle, String chunkId, String topicTitle, long startMs, long endMs,
                                List<String> segmentIds, List<String> speakerIds, List<SourceFragment> sourceFragments, String content) {
        public ReadableChunk(String documentId, String transcriptionTaskId, String documentTitle, String chunkId, long startMs, long endMs, List<String> segmentIds, String content) {
            this(documentId, transcriptionTaskId, documentTitle, chunkId, null, startMs, endMs, segmentIds, List.of(), List.of(), content);
        }
    }
}
