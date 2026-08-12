package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.UserMemoryCategory;
import com.voicenote.provider.ProviderException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import java.util.*;

@Component
@ConditionalOnProperty(name = "app.memory.enabled", havingValue = "true")
public class QdrantUserMemoryVectorStore implements UserMemoryVectorStore {
    private static final String DENSE = "dense";
    private static final String BM25 = "bm25";
    private final AppProperties properties;
    private final RestClient client;
    public QdrantUserMemoryVectorStore(AppProperties properties) {
        this.properties = properties;
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.getKnowledge().getQdrantUrl());
        String key = properties.getKnowledge().getQdrantApiKey();
        if (key != null && !key.isBlank()) builder.defaultHeader("api-key", key);
        this.client = builder.build();
    }
    @Override public void ensureCollection() {
        Map<String, Object> body = Map.of("vectors", Map.of(DENSE, Map.of("size", properties.getDashscope().getEmbeddingDimension(), "distance", "Cosine")),
                "sparse_vectors", Map.of(BM25, Map.of("modifier", "idf")));
        try { client.put().uri("/collections/{collection}", properties.getMemory().getCollection()).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity(); }
        catch (RestClientResponseException exception) { if (exception.getStatusCode().value() != 409) throw unavailable(); }
        for (String field : List.of("ownerId", "memoryId", "versionId", "category")) createIndex(field);
    }
    @Override public void upsert(String ownerId, String memoryId, String versionId, UserMemoryCategory category, String content, List<Double> denseVector) {
        Map<String, Object> bm25 = Map.of("text", content, "model", "qdrant/bm25", "options", Map.of("language", "none", "tokenizer", "multilingual"));
        Map<String, Object> point = Map.of("id", versionId, "vector", Map.of(DENSE, denseVector, BM25, bm25),
                "payload", Map.of("ownerId", ownerId, "memoryId", memoryId, "versionId", versionId, "category", category.name()));
        try { client.put().uri("/collections/{collection}/points?wait=true", properties.getMemory().getCollection())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("points", List.of(point))).retrieve().toBodilessEntity(); }
        catch (RuntimeException exception) { throw unavailable(); }
    }
    @Override public void deleteMemory(String ownerId, String memoryId) {
        Map<String, Object> filter = filter(ownerId, memoryId, List.of());
        try { client.post().uri("/collections/{collection}/points/delete?wait=true", properties.getMemory().getCollection())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("filter", filter)).retrieve().toBodilessEntity(); }
        catch (RuntimeException exception) { throw unavailable(); }
    }
    @Override public List<MemoryHit> search(String ownerId, String query, List<Double> denseVector, List<UserMemoryCategory> categories, int limit) {
        Map<String, Object> filter = filter(ownerId, null, categories);
        Map<String, Object> bm25 = Map.of("text", query, "model", "qdrant/bm25", "options", Map.of("language", "none", "tokenizer", "multilingual"));
        Map<String, Object> body = Map.of("prefetch", List.of(
                        Map.of("query", denseVector, "using", DENSE, "filter", filter, "limit", Math.max(limit, 24)),
                        Map.of("query", bm25, "using", BM25, "filter", filter, "limit", Math.max(limit, 24))),
                "query", Map.of("rrf", Map.of("k", 60)), "limit", limit, "with_payload", true);
        try {
            JsonNode response = client.post().uri("/collections/{collection}/points/query", properties.getMemory().getCollection())
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
            JsonNode points = response == null ? null : response.path("result").path("points");
            if (points == null || !points.isArray()) return List.of();
            List<MemoryHit> hits = new ArrayList<>();
            for (JsonNode point : points) hits.add(new MemoryHit(point.path("payload").path("memoryId").asText(),
                    point.path("payload").path("versionId").asText(), point.path("score").asDouble()));
            return List.copyOf(hits);
        } catch (RuntimeException exception) { throw unavailable(); }
    }
    private Map<String, Object> filter(String ownerId, String memoryId, List<UserMemoryCategory> categories) {
        List<Map<String, Object>> must = new ArrayList<>();
        must.add(Map.of("key", "ownerId", "match", Map.of("value", ownerId)));
        if (memoryId != null) must.add(Map.of("key", "memoryId", "match", Map.of("value", memoryId)));
        if (categories != null && !categories.isEmpty()) must.add(Map.of("key", "category", "match", Map.of("any", categories.stream().map(Enum::name).toList())));
        return Map.of("must", must);
    }
    private void createIndex(String field) {
        try { client.put().uri("/collections/{collection}/index", properties.getMemory().getCollection()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("field_name", field, "field_schema", "keyword")).retrieve().toBodilessEntity(); }
        catch (RestClientResponseException exception) { if (exception.getStatusCode().value() != 409) throw unavailable(); }
    }
    private ProviderException unavailable() { return new ProviderException(ProviderException.Kind.RETRYABLE_REJECTION, "MEMORY_VECTOR_UNAVAILABLE", "Long-term memory search is temporarily unavailable"); }
}
