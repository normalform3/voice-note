package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.KnowledgeChunk;
import com.voicenote.domain.KnowledgeDocument;
import com.voicenote.domain.KnowledgeIndexVersion;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class QdrantKnowledgeVectorStoreTest {
    @Test
    void sendsTheConfiguredApiKeyToQdrant() throws Exception {
        AtomicReference<String> apiKey = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/healthz", exchange -> {
            apiKey.set(exchange.getRequestHeaders().getFirst("api-key"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            AppProperties properties = new AppProperties();
            properties.getKnowledge().setQdrantUrl("http://127.0.0.1:" + server.getAddress().getPort());
            properties.getKnowledge().setQdrantApiKey("qdrant-test-key");

            new QdrantKnowledgeVectorStore(properties, new ObjectMapper()).ensureAvailable();

            assertThat(apiKey.get()).isEqualTo("qdrant-test-key");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliesOwnerFilterToBothHybridPrefetches() {
        Map<String, Object> body = QdrantKnowledgeVectorStore.searchBody("owner-a", "会议待办", List.of(0.1, 0.2), 3);
        List<Map<String, Object>> prefetch = (List<Map<String, Object>>) body.get("prefetch");
        for (Map<String, Object> request : prefetch) {
            Map<String, Object> filter = (Map<String, Object>) request.get("filter");
            List<Map<String, Object>> must = (List<Map<String, Object>>) filter.get("must");
            Map<String, Object> match = (Map<String, Object>) must.get(0).get("match");
            assertThat(must.get(0).get("key")).isEqualTo("ownerId");
            assertThat(match.get("value")).isEqualTo("owner-a");
            assertThat(must.get(1).get("key")).isEqualTo("searchable");
            assertThat(((Map<String, Object>) must.get(1).get("match")).get("value")).isEqualTo(true);
        }
        assertThat(body.get("query")).isEqualTo(Map.of("rrf", Map.of("k", 60)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesThePersistedChunkTextForBm25AlongsideTheDenseVector() throws Exception {
        AppProperties properties = new AppProperties();
        properties.getKnowledge().setQdrantUrl("http://localhost");
        QdrantKnowledgeVectorStore store = new QdrantKnowledgeVectorStore(properties, new ObjectMapper());
        KnowledgeDocument document = new KnowledgeDocument("owner-a", "task-a", 1, "Java 面试");
        KnowledgeIndexVersion version = new KnowledgeIndexVersion(document.getId(), 1, "formal-a", 1, "hash");
        String content = "# Java 面试\n## Redis\n面试官：如何解决缓存穿透？\n候选人：使用缓存空值和布隆过滤器。\n";
        KnowledgeChunk chunk = new KnowledgeChunk(document.getId(), version.getId(), 0, 0, 2_000, "[\"segment-a\"]", "[\"block-a\"]",
                "Redis", "[\"SPEAKER_0\",\"SPEAKER_1\"]", "[]", "[]", 42, false, content, "content-hash");

        Map<String, Object> point = store.point(document, version, chunk, List.of(0.1, 0.2), List.of("topic-a"));

        Map<String, Object> vectors = (Map<String, Object>) point.get("vector");
        assertThat(vectors.get("dense")).isEqualTo(List.of(0.1, 0.2));
        Map<String, Object> bm25 = (Map<String, Object>) vectors.get("bm25");
        assertThat(bm25.get("text")).isEqualTo(content);
        assertThat(bm25.get("model")).isEqualTo("qdrant/bm25");
        assertThat(((Map<String, Object>) point.get("payload")).get("searchable")).isEqualTo(false);
    }
}
