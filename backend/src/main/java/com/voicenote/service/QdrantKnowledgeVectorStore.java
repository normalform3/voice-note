package com.voicenote.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.KnowledgeChunk;
import com.voicenote.domain.KnowledgeDocument;
import com.voicenote.provider.ProviderException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.knowledge.enabled", havingValue = "true")
public class QdrantKnowledgeVectorStore implements KnowledgeVectorStore {
    private static final String DENSE = "dense";
    private static final String BM25 = "bm25";
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final RestClient client;

    public QdrantKnowledgeVectorStore(AppProperties properties, ObjectMapper mapper) {
        this.properties = properties; this.mapper = mapper;
        this.client = RestClient.builder().baseUrl(properties.getKnowledge().getQdrantUrl()).build();
    }

    @Override public void ensureCollection() {
        Map<String, Object> body = Map.of(
                "vectors", Map.of(DENSE, Map.of("size", properties.getDashscope().getEmbeddingDimension(), "distance", "Cosine")),
                "sparse_vectors", Map.of(BM25, Map.of("modifier", "idf")));
        try { client.put().uri("/collections/{collection}", properties.getKnowledge().getCollection()).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity(); }
        catch (RestClientResponseException exception) { if (exception.getStatusCode().value() != 409) throw unavailable(exception); }
        createPayloadIndex("ownerId"); createPayloadIndex("documentId"); createPayloadIndex("taskId");
    }

    @Override public void upsert(KnowledgeDocument document, KnowledgeChunk chunk, List<Double> denseVector) {
        try {
            List<String> segmentIds = mapper.readValue(chunk.getSegmentIds(), new TypeReference<>() { });
            List<String> blockIds = chunk.getOrganizedDocumentBlockIds() == null ? List.of() : mapper.readValue(chunk.getOrganizedDocumentBlockIds(), new TypeReference<>() { });
            List<String> speakerIds = chunk.getSpeakerIds() == null ? List.of() : mapper.readValue(chunk.getSpeakerIds(), new TypeReference<>() { });
            List<String> contextSegmentIds = chunk.getContextSegmentIds() == null ? List.of() : mapper.readValue(chunk.getContextSegmentIds(), new TypeReference<>() { });
            Map<String, Object> bm25 = new LinkedHashMap<>();
            bm25.put("text", chunk.getTextContent()); bm25.put("model", "qdrant/bm25");
            bm25.put("options", Map.of("language", "none", "tokenizer", "multilingual"));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ownerId", document.getOwnerId()); payload.put("documentId", document.getId()); payload.put("taskId", document.getTranscriptionTaskId());
            payload.put("chunkId", chunk.getId()); payload.put("segmentIds", segmentIds); payload.put("organizedDocumentBlockIds", blockIds);
            payload.put("startMs", chunk.getStartMs()); payload.put("endMs", chunk.getEndMs());
            payload.put("topic", chunk.getTopicTitle()); payload.put("speakerIds", speakerIds); payload.put("contextSegmentIds", contextSegmentIds);
            payload.put("tokenCount", chunk.getTokenCount()); payload.put("oversized", chunk.isOversized());
            Map<String, Object> point = Map.of(
                    "id", chunk.getId(),
                    "vector", Map.of(DENSE, denseVector, BM25, bm25),
                    "payload", payload);
            client.put().uri("/collections/{collection}/points?wait=true", properties.getKnowledge().getCollection())
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("points", List.of(point))).retrieve().toBodilessEntity();
        } catch (ProviderException exception) { throw exception; }
        catch (Exception exception) { throw unavailable(exception); }
    }

    @Override public void deleteDocument(String ownerId, String documentId) {
        Map<String, Object> filter = Map.of("must", List.of(
                Map.of("key", "ownerId", "match", Map.of("value", ownerId)),
                Map.of("key", "documentId", "match", Map.of("value", documentId))));
        try {
            client.post().uri("/collections/{collection}/points/delete?wait=true", properties.getKnowledge().getCollection())
                    .contentType(MediaType.APPLICATION_JSON).body(Map.of("filter", filter)).retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) { throw unavailable(exception); }
        catch (RuntimeException exception) { throw unavailable(exception); }
    }

    @Override public List<RetrievalHit> search(String ownerId, String query, List<Double> denseVector, int limit) {
        Map<String, Object> body = searchBody(ownerId, query, denseVector, limit);
        try {
            JsonNode response = client.post().uri("/collections/{collection}/points/query", properties.getKnowledge().getCollection())
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
            JsonNode points = response == null ? null : response.path("result").path("points");
            if (points == null || !points.isArray()) return List.of();
            java.util.ArrayList<RetrievalHit> hits = new java.util.ArrayList<>();
            for (JsonNode point : points) {
                JsonNode payload = point.path("payload");
                hits.add(new RetrievalHit(payload.path("chunkId").asText(), payload.path("documentId").asText(), payload.path("startMs").asLong(), payload.path("endMs").asLong(), point.path("score").asDouble()));
            }
            return hits;
        } catch (RestClientResponseException exception) { throw unavailable(exception); }
        catch (RuntimeException exception) { throw unavailable(exception); }
    }

    static Map<String, Object> searchBody(String ownerId, String query, List<Double> denseVector, int limit) {
        Map<String, Object> filter = Map.of("must", List.of(Map.of("key", "ownerId", "match", Map.of("value", ownerId))));
        Map<String, Object> bm25Query = Map.of("text", query, "model", "qdrant/bm25", "options", Map.of("language", "none", "tokenizer", "multilingual"));
        return Map.of(
                "prefetch", List.of(
                        Map.of("query", denseVector, "using", DENSE, "filter", filter, "limit", Math.max(limit * 3, 12)),
                        Map.of("query", bm25Query, "using", BM25, "filter", filter, "limit", Math.max(limit * 3, 12))),
                "query", Map.of("fusion", "rrf"), "limit", limit, "with_payload", true);
    }

    private void createPayloadIndex(String field) {
        try { client.put().uri("/collections/{collection}/index", properties.getKnowledge().getCollection()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("field_name", field, "field_schema", "keyword")).retrieve().toBodilessEntity(); }
        catch (RestClientResponseException exception) { throw unavailable(exception); }
    }
    private ProviderException unavailable(Exception exception) {
        return new ProviderException(ProviderException.Kind.RETRYABLE_REJECTION, "QDRANT_UNAVAILABLE", "Qdrant request failed: " + exception.getMessage());
    }
}
