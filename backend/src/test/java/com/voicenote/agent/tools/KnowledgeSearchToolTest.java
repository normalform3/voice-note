package com.voicenote.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voicenote.agent.AgentExecutionContext;
import com.voicenote.agent.AgentSkill;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.AgentScopeType;
import com.voicenote.service.KnowledgeSearchService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeSearchToolTest {
    @Test
    void dropsWholeChunksToRespectTheSerializedByteBudget() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        KnowledgeSearchService search = mock(KnowledgeSearchService.class);
        AppProperties properties = new AppProperties();
        properties.getAgent().setMaxToolOutputBytes(1_600);
        String firstContent = "甲".repeat(300);
        String secondContent = "乙".repeat(2_000);
        var first = new KnowledgeSearchService.ReadableChunk("document", "task", "录音", "chunk-1", 0, 1_000, List.of("segment-1"), firstContent);
        var second = new KnowledgeSearchService.ReadableChunk("document", "task", "录音", "chunk-2", 1_000, 2_000, List.of("segment-2"), secondContent);
        when(search.searchScoped(eq("owner"), anyList(), eq("query"), eq(2))).thenReturn(new KnowledgeSearchService.ScopedSearchResult(
                List.of(first, second), List.of("task"), List.of(), false, null, null));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(mapper, search, properties);
        AgentExecutionContext.ScopeDocument scoped = new AgentExecutionContext.ScopeDocument("task", "document", "version", "录音",
                Instant.EPOCH, "OTHER", null, List.of(), 1, mapper.createObjectNode());
        AgentExecutionContext context = new AgentExecutionContext("run", "owner", AgentScopeType.SELECTED_DOCUMENTS, ZoneId.of("Asia/Shanghai"),
                new AgentSkill("test", "1", "Test", "Test", List.of(), "", List.of("knowledge_search"), false),
                List.of(scoped), Instant.now().plusSeconds(30));
        ObjectNode arguments = mapper.createObjectNode(); arguments.put("query", "query"); arguments.put("perDocumentLimit", 2);

        var result = tool.execute(context, arguments);

        assertThat(mapper.writeValueAsBytes(result.payload()).length).isLessThanOrEqualTo(1_600);
        assertThat(result.payload().path("chunks")).hasSize(1);
        assertThat(result.payload().path("chunks").get(0).path("content").asText()).isEqualTo(firstContent);
        assertThat(result.payload().path("truncated").asBoolean()).isTrue();
        assertThat(result.payload().path("truncationReason").asText()).isEqualTo("toolOutputByteLimit");
        assertThat(context.checkpointCoverage().searchedDocumentIds()).containsExactly("task");
    }
}
