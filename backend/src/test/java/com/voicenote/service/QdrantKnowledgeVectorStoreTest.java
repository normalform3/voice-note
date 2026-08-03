package com.voicenote.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class QdrantKnowledgeVectorStoreTest {
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
        }
        assertThat(body.get("query")).isEqualTo(Map.of("fusion", "rrf"));
    }
}
