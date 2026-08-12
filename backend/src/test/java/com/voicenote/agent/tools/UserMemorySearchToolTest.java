package com.voicenote.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.AgentToolArgumentValidator;
import com.voicenote.agent.AgentExecutionContext;
import com.voicenote.agent.AgentSkill;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.AgentScopeType;
import com.voicenote.domain.UserMemoryCategory;
import com.voicenote.provider.TextEmbeddingClient;
import com.voicenote.service.UserMemoryService;
import com.voicenote.service.UserMemoryVectorStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserMemorySearchToolTest {
    @Test
    void schemaDoesNotAllowOwnerOrDirectMemoryIdentifiers() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AppProperties properties = new AppProperties(); properties.getMemory().setEnabled(true);
        UserMemorySearchTool tool = new UserMemorySearchTool(mapper, properties, mock(TextEmbeddingClient.class),
                mock(UserMemoryVectorStore.class), mock(UserMemoryService.class));

        assertThat(tool.definition().parameters().path("properties").has("ownerId")).isFalse();
        assertThat(tool.definition().parameters().path("properties").has("memoryId")).isFalse();
        assertThatThrownBy(() -> AgentToolArgumentValidator.validate(tool.definition().parameters(),
                mapper.readTree("{\"query\":\"偏好\",\"ownerId\":\"other-user\"}")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ownerId");

        AgentSkill skill = new AgentSkill("knowledge-qa", "v1", "问答", "", List.of(), "", List.of("finalize_answer"), false);
        assertThat(tool.available(new AgentExecutionContext("run", "owner", AgentScopeType.ALL_DOCUMENTS, ZoneId.of("Asia/Shanghai"),
                skill, List.of(), Instant.now().plusSeconds(30), null, false))).isFalse();
        assertThat(tool.available(new AgentExecutionContext("run", "owner", AgentScopeType.ALL_DOCUMENTS, ZoneId.of("Asia/Shanghai"),
                skill, List.of(), Instant.now().plusSeconds(30), null, true))).isTrue();
    }

    @Test
    void searchesWithTheAuthenticatedOwnerAndReturnsOnlyMysqlValidatedHits() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AppProperties properties = new AppProperties(); properties.getMemory().setEnabled(true);
        TextEmbeddingClient embeddings = mock(TextEmbeddingClient.class);
        UserMemoryVectorStore vectors = mock(UserMemoryVectorStore.class);
        UserMemoryService memories = mock(UserMemoryService.class);
        when(embeddings.embedQuery("回答风格")).thenReturn(List.of(0.1, 0.2));
        var vectorHit = new UserMemoryVectorStore.MemoryHit("memory-a", "version-a", 0.9);
        when(vectors.search("owner-a", "回答风格", List.of(0.1, 0.2), List.of(UserMemoryCategory.WORK_STYLE), 3))
                .thenReturn(List.of(vectorHit, new UserMemoryVectorStore.MemoryHit("stale", "stale-version", 0.95)));
        when(memories.validateHits("owner-a", List.of(vectorHit, new UserMemoryVectorStore.MemoryHit("stale", "stale-version", 0.95))))
                .thenReturn(List.of(new UserMemoryService.SearchResult("memory-a", "version-a", UserMemoryCategory.WORK_STYLE, "先给结论", 0.9)));
        UserMemorySearchTool tool = new UserMemorySearchTool(mapper, properties, embeddings, vectors, memories);
        AgentSkill skill = new AgentSkill("knowledge-qa", "v1", "问答", "", List.of(), "", List.of("finalize_answer"), false);
        AgentExecutionContext context = new AgentExecutionContext("run", "owner-a", AgentScopeType.ALL_DOCUMENTS, ZoneId.of("Asia/Shanghai"),
                skill, List.of(), Instant.now().plusSeconds(30), null, true);

        var result = tool.execute(context, mapper.readTree("{\"query\":\"回答风格\",\"categories\":[\"WORK_STYLE\"],\"limit\":3}"));

        assertThat(result.payload().path("memories")).hasSize(1);
        assertThat(result.payload().path("memories").path(0).path("content").asText()).isEqualTo("先给结论");
        assertThat(context.evidence().all()).singleElement().satisfies(source -> {
            assertThat(source.memoryId()).isEqualTo("memory-a");
            assertThat(source.memoryVersionId()).isEqualTo("version-a");
        });
    }
}
