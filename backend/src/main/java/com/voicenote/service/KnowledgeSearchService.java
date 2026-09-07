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
import java.util.function.Function;
import java.util.stream.Collectors;

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
        Map<String, KnowledgeChunk> storedChunks = byId(chunks.findAllById(hits.stream().map(KnowledgeVectorStore.RetrievalHit::chunkId).toList()), KnowledgeChunk::getId);
        Map<String, KnowledgeDocument> storedDocuments = byId(documents.findAllById(hits.stream().map(KnowledgeVectorStore.RetrievalHit::documentId).toList()), KnowledgeDocument::getId);
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
        KnowledgeChunk chunk = chunks.findById(chunkId).orElseThrow(KnowledgeSearchService::notFound);
        KnowledgeDocument document = documents.findById(chunk.getKnowledgeDocumentId()).filter(value -> value.getOwnerId().equals(ownerId) && value.getStatus() == KnowledgeDocumentStatus.READY
                        && Objects.equals(value.getActiveIndexVersionId(), chunk.getKnowledgeIndexVersionId()))
                .orElseThrow(KnowledgeSearchService::notFound);
        return readable(document, chunk);
    }

    /** Reads a chunk from the exact index generation captured by an Agent Run, including a generation retired after the run started. */
    public ReadableChunk readScopedDocumentChunk(String ownerId, String chunkId, String expectedIndexVersionId) {
        KnowledgeChunk chunk = chunks.findById(chunkId).filter(value -> Objects.equals(value.getKnowledgeIndexVersionId(), expectedIndexVersionId)).orElseThrow(KnowledgeSearchService::notFound);
        KnowledgeDocument document = documents.findById(chunk.getKnowledgeDocumentId()).filter(value -> value.getOwnerId().equals(ownerId)).orElseThrow(KnowledgeSearchService::notFound);
        return readable(document, chunk);
    }

    public ScopedSearchResult searchScoped(String ownerId, List<ScopedDocument> scope, String query, int perDocumentLimit) {
        if (scope.isEmpty()) return new ScopedSearchResult(List.of(), List.of(), List.of(), true, "noIndexedDocuments", "noIndexedDocuments");
        if (scope.size() > 12) throw new ApiException(HttpStatus.BAD_REQUEST, "SEARCH_SCOPE_TOO_LARGE", "One knowledge search may target at most 12 documents; use document overviews first");
        int quota = Math.max(1, Math.min(perDocumentLimit, 4));
        int candidateLimit = Math.max(quota, properties.getKnowledge().getRetrievalCandidatePerDocument());
        List<Double> queryVector = embeddings.embedQuery(query);
        Map<String, ScopedDocument> scopeByDocument = scope.stream().collect(Collectors.toMap(ScopedDocument::documentId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<String, KnowledgeChunk> candidates = new LinkedHashMap<>();
        Map<String, Double> retrievalScores = new HashMap<>();

        for (ScopedDocument scoped : scope) {
            List<KnowledgeVectorStore.RetrievalHit> hits = vectors.searchScoped(ownerId, scoped.documentId(), scoped.indexVersionId(), query, queryVector, candidateLimit);
            Map<String, KnowledgeChunk> stored = byId(chunks.findAllById(hits.stream().map(KnowledgeVectorStore.RetrievalHit::chunkId).toList()), KnowledgeChunk::getId);
            for (KnowledgeVectorStore.RetrievalHit hit : hits) {
                KnowledgeChunk chunk = stored.get(hit.chunkId());
                if (chunk == null || !Objects.equals(chunk.getKnowledgeDocumentId(), scoped.documentId()) || !Objects.equals(chunk.getKnowledgeIndexVersionId(), scoped.indexVersionId())) continue;
                candidates.putIfAbsent(chunk.getId(), chunk);
                retrievalScores.merge(chunk.getId(), hit.score(), Math::max);
            }
        }

        List<KnowledgeChunk> pool = List.copyOf(candidates.values());
        if (pool.isEmpty()) {
            return new ScopedSearchResult(List.of(), List.of(), scope.stream().map(ScopedDocument::taskId).toList(), true, "noHybridHits", null);
        }
        TextRerankClient.RerankResult reranked = reranker.rerank(query, pool.stream().map(chunk ->
                new TextRerankClient.Candidate(chunk.getId(), chunk.getDenseText(), retrievalScores.getOrDefault(chunk.getId(), 0d))).toList());
        Map<String, Integer> rerankPosition = new HashMap<>();
        for (int index = 0; index < reranked.ranked().size(); index++) rerankPosition.put(reranked.ranked().get(index).id(), index);
        List<KnowledgeChunk> ordered = pool.stream().sorted(Comparator
                .comparingInt((KnowledgeChunk value) -> rerankPosition.getOrDefault(value.getId(), Integer.MAX_VALUE))
                .thenComparing(Comparator.comparingDouble((KnowledgeChunk value) -> retrievalScores.getOrDefault(value.getId(), 0d)).reversed()))
                .toList();

        List<KnowledgeChunk> mandatorySeeds = new ArrayList<>();
        for (ScopedDocument requested : scope) ordered.stream().filter(value -> value.getKnowledgeDocumentId().equals(requested.documentId())).findFirst().ifPresent(mandatorySeeds::add);
        Set<String> mandatoryIds = mandatorySeeds.stream().map(KnowledgeChunk::getId).collect(Collectors.toSet());
        List<KnowledgeChunk> extraSeeds = new ArrayList<>();
        Map<String, Integer> seedCounts = new HashMap<>();
        mandatorySeeds.forEach(value -> seedCounts.merge(value.getKnowledgeDocumentId(), 1, Integer::sum));
        for (KnowledgeChunk chunk : ordered) {
            if (mandatoryIds.contains(chunk.getId()) || seedCounts.getOrDefault(chunk.getKnowledgeDocumentId(), 0) >= quota) continue;
            extraSeeds.add(chunk); seedCounts.merge(chunk.getKnowledgeDocumentId(), 1, Integer::sum);
        }

        LinkedHashMap<String, KnowledgeChunk> contextOrder = new LinkedHashMap<>();
        mandatorySeeds.forEach(value -> contextOrder.put(value.getId(), value));
        neighboursForSeeds(mandatorySeeds, scopeByDocument).forEach(value -> contextOrder.putIfAbsent(value.getId(), value));
        extraSeeds.forEach(value -> contextOrder.putIfAbsent(value.getId(), value));
        neighboursForSeeds(extraSeeds, scopeByDocument).forEach(value -> contextOrder.putIfAbsent(value.getId(), value));

        PackResult packed = pack(contextOrder.values());
        List<ReadableChunk> readable = new ArrayList<>();
        for (KnowledgeChunk chunk : packed.chunks()) {
            ScopedDocument scoped = scopeByDocument.get(chunk.getKnowledgeDocumentId());
            if (scoped != null) readable.add(readScopedDocumentChunk(ownerId, chunk.getId(), scoped.indexVersionId()));
        }
        List<String> covered = readable.stream().map(ReadableChunk::transcriptionTaskId).distinct().toList();
        List<String> uncovered = scope.stream().map(ScopedDocument::taskId).filter(taskId -> !covered.contains(taskId)).toList();
        return new ScopedSearchResult(List.copyOf(readable), covered, uncovered, reranked.fallback(), reranked.limitation(), packed.truncationReason());
    }

    /** Expands eligible seeds by one neighbouring chunk in the same Topic using the same rules as Agent knowledge_search. */
    public List<ReadableChunk> readExpandedContext(String ownerId, List<SearchHit> hits) {
        List<SearchHit> requestedSeeds = hits.stream().limit(properties.getKnowledge().getRetrievalSeedLimit()).toList();
        if (requestedSeeds.isEmpty()) return List.of();
        Map<String, KnowledgeChunk> known = byId(chunks.findAllById(requestedSeeds.stream().map(SearchHit::chunkId).toList()), KnowledgeChunk::getId);
        List<KnowledgeChunk> seeds = requestedSeeds.stream().map(value -> known.get(value.chunkId())).filter(Objects::nonNull).toList();
        Map<String, ScopedDocument> scope = requestedSeeds.stream().collect(Collectors.toMap(SearchHit::documentId,
                value -> new ScopedDocument(value.transcriptionTaskId(), value.documentId(), value.indexVersionId()), (left, right) -> left, LinkedHashMap::new));
        LinkedHashMap<String, KnowledgeChunk> contextOrder = new LinkedHashMap<>();
        for (KnowledgeChunk seed : seeds) {
            contextOrder.putIfAbsent(seed.getId(), seed);
            neighboursForSeeds(List.of(seed), scope).forEach(value -> contextOrder.putIfAbsent(value.getId(), value));
        }
        PackResult packed = pack(contextOrder.values());
        List<ReadableChunk> output = new ArrayList<>();
        for (KnowledgeChunk chunk : packed.chunks()) output.add(readDocumentChunk(ownerId, chunk.getId()));
        return List.copyOf(output);
    }

    private List<KnowledgeChunk> neighboursForSeeds(List<KnowledgeChunk> seeds, Map<String, ScopedDocument> scopeByDocument) {
        List<KnowledgeChunk> expandable = seeds.stream().filter(this::expandsNeighbours).toList();
        if (expandable.isEmpty()) return List.of();
        List<KnowledgeChunkTopic> seedLinks = safe(chunkTopics.findByKnowledgeChunkIdIn(expandable.stream().map(KnowledgeChunk::getId).toList()));
        if (seedLinks.isEmpty()) return List.of();
        Map<String, List<KnowledgeChunkTopic>> seedLinksByChunk = seedLinks.stream().collect(Collectors.groupingBy(KnowledgeChunkTopic::getKnowledgeChunkId));
        Set<String> topicIds = seedLinks.stream().map(KnowledgeChunkTopic::getKnowledgeTopicId).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, List<KnowledgeChunkTopic>> linksByTopic = safe(chunkTopics.findByKnowledgeTopicIdIn(topicIds)).stream()
                .collect(Collectors.groupingBy(KnowledgeChunkTopic::getKnowledgeTopicId));
        LinkedHashSet<String> neighbourIds = new LinkedHashSet<>();
        for (KnowledgeChunk seed : expandable) {
            for (KnowledgeChunkTopic seedLink : seedLinksByChunk.getOrDefault(seed.getId(), List.of())) {
                linksByTopic.getOrDefault(seedLink.getKnowledgeTopicId(), List.of()).stream()
                        .filter(link -> Math.abs(link.getChunkIndexInTopic() - seedLink.getChunkIndexInTopic()) == 1)
                        .sorted(Comparator.comparingInt(KnowledgeChunkTopic::getChunkIndexInTopic))
                        .map(KnowledgeChunkTopic::getKnowledgeChunkId).forEach(neighbourIds::add);
            }
        }
        Map<String, KnowledgeChunk> loaded = byId(chunks.findAllById(neighbourIds), KnowledgeChunk::getId);
        List<KnowledgeChunk> output = new ArrayList<>();
        for (String id : neighbourIds) {
            KnowledgeChunk candidate = loaded.get(id);
            if (candidate == null) continue;
            ScopedDocument scoped = scopeByDocument.get(candidate.getKnowledgeDocumentId());
            if (scoped != null && Objects.equals(candidate.getKnowledgeIndexVersionId(), scoped.indexVersionId())) output.add(candidate);
        }
        return List.copyOf(output);
    }

    private boolean expandsNeighbours(KnowledgeChunk chunk) {
        return chunk.getChunkProfile().expandNeighbours() && chunk.getChunkKind() == KnowledgeChunkKind.NARRATIVE;
    }

    private PackResult pack(Collection<KnowledgeChunk> candidates) {
        List<KnowledgeChunk> output = new ArrayList<>();
        int totalTokens = 0; boolean tokenLimited = false; boolean chunkLimited = false;
        for (KnowledgeChunk chunk : candidates) {
            if (output.size() >= properties.getKnowledge().getRetrievalContextMaxChunks()) { chunkLimited = true; break; }
            int nextTokens = chunk.getTokenCount() == null ? approximateTokens(chunk.getDenseText()) : Math.max(1, chunk.getTokenCount());
            if (totalTokens + nextTokens > properties.getKnowledge().getRetrievalContextMaxTokens()) { tokenLimited = true; continue; }
            output.add(chunk); totalTokens += nextTokens;
        }
        String reason = tokenLimited ? "contextTokenLimit" : chunkLimited ? "contextChunkLimit" : null;
        return new PackResult(List.copyOf(output), reason);
    }

    private ReadableChunk readable(KnowledgeDocument document, KnowledgeChunk chunk) {
        try {
            List<String> segmentIds = mapper.readValue(chunk.getSegmentIds(), new TypeReference<>() { });
            List<String> contextSegmentIds = chunk.getContextSegmentIds() == null ? List.of() : mapper.readValue(chunk.getContextSegmentIds(), new TypeReference<>() { });
            List<String> speakerIds = chunk.getSpeakerIds() == null ? List.of() : mapper.readValue(chunk.getSpeakerIds(), new TypeReference<>() { });
            List<SourceFragment> fragments = chunk.getSourceFragments() == null ? List.of() : mapper.readValue(chunk.getSourceFragments(), new TypeReference<>() { });
            return new ReadableChunk(document.getId(), document.getTranscriptionTaskId(), document.getTitle(), chunk.getId(), chunk.getTopicTitle(), chunk.getStartMs(), chunk.getEndMs(),
                    segmentIds, contextSegmentIds, speakerIds, fragments, chunk.getChunkProfile(), chunk.getChunkKind(), chunk.getTokenCount(), chunk.isOversized(), chunk.getTextContent());
        } catch (Exception exception) { throw new IllegalStateException("Knowledge chunk contains invalid segment references", exception); }
    }

    private static int approximateTokens(String value) { return Math.max(1, value == null ? 1 : value.codePointCount(0, value.length())); }
    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }
    private static <T> Map<String, T> byId(Iterable<T> values, Function<T, String> id) {
        Map<String, T> output = new LinkedHashMap<>();
        if (values != null) values.forEach(value -> output.put(id.apply(value), value));
        return output;
    }
    private static ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "CHUNK_NOT_FOUND", "Knowledge chunk was not found"); }

    public record SearchHit(String documentId, String transcriptionTaskId, String documentTitle, String indexVersionId, String chunkId, long startMs, long endMs, double score) { }
    public record ScopedDocument(String taskId, String documentId, String indexVersionId) { }
    public record ScopedSearchResult(List<ReadableChunk> chunks, List<String> coveredDocumentIds, List<String> uncoveredDocumentIds,
                                     boolean rerankFallback, String limitation, String truncationReason) { }
    public record SourceFragment(String segmentId, String speakerId, long startMs, long endMs, String text) { }
    public record ReadableChunk(String documentId, String transcriptionTaskId, String documentTitle, String chunkId, String topicTitle, long startMs, long endMs,
                                List<String> segmentIds, List<String> contextSegmentIds, List<String> speakerIds, List<SourceFragment> sourceFragments,
                                ChunkProfile profile, KnowledgeChunkKind chunkKind, Integer tokenCount, boolean oversized, String content) {
        public ReadableChunk(String documentId, String transcriptionTaskId, String documentTitle, String chunkId, long startMs, long endMs, List<String> segmentIds, String content) {
            this(documentId, transcriptionTaskId, documentTitle, chunkId, null, startMs, endMs, segmentIds, List.of(), List.of(), List.of(),
                    ChunkProfile.MONOLOGUE, KnowledgeChunkKind.NARRATIVE, null, false, content);
        }
    }
    private record PackResult(List<KnowledgeChunk> chunks, String truncationReason) { }
}
