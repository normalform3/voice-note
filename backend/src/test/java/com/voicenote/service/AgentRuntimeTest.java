package com.voicenote.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.*;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.provider.AgentModelClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentRuntimeTest {
    @Test
    @SuppressWarnings("unchecked")
    void reservesTheLastModelCallForFinalizeAnswer() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentTool lookup = tool(mapper, "lookup", true, false);
        AgentTool finalize = tool(mapper, "finalize_answer", false, true);
        McpReadOnlyToolProvider mcp = mock(McpReadOnlyToolProvider.class);
        when(mcp.tools()).thenReturn(List.of());
        AgentToolRegistry toolRegistry = new AgentToolRegistry(List.of(lookup, finalize), mcp);
        AgentSkill skill = new AgentSkill("knowledge-qa", "v1", "Knowledge QA", "", List.of(), "Use evidence",
                List.of("lookup", "finalize_answer"), false);
        String skillSnapshot = mapper.writeValueAsString(skill);
        KnowledgeRun run = new KnowledgeRun("owner", "question", "model", AgentScopeType.CURRENT_DOCUMENT, "Asia/Shanghai",
                skill.id(), skill.version(), skillSnapshot, Hashing.sha256(skillSnapshot), 3, 4, 4);
        run.queue(); run.start(); run.consumeModelCall(); run.consumeModelCall();
        KnowledgeRunDocument document = new KnowledgeRunDocument(run.getId(), "task", "document", "index-v1",
                "{\"title\":\"Note\",\"occurredAt\":\"2026-01-01T00:00:00Z\",\"sceneType\":\"MEETING\",\"tags\":[],\"transcriptVersion\":1}");
        AgentState state = AgentState.initial(AgentPhase.MODEL_DECISION, skill.id(), skill.version(), Hashing.sha256(skillSnapshot),
                        List.of(AgentModelClient.AgentMessage.system("system"), AgentModelClient.AgentMessage.user("question")))
                .withFrozenContext("model", "system", skillSnapshot,
                        List.of(new AgentState.DocumentSnapshot("task", "document", "index-v1", document.getMetadataSnapshot())),
                        3, 4, 4, 120_000).withRuntimeStats(2, 0, 0, 0);
        KnowledgeAgentService runs = mock(KnowledgeAgentService.class);
        when(runs.ownedRun("owner", run.getId())).thenReturn(run);
        when(runs.loadCurrentState(run.getId(), run.getExecutionEpoch())).thenReturn(state);
        when(runs.runDocuments(run.getId())).thenReturn(List.of(document));
        when(runs.storedSources(run.getId())).thenReturn(List.of());
        when(runs.beginModelStep(eq(run.getId()), eq(run.getExecutionEpoch()), anyString()))
                .thenReturn(new KnowledgeAgentService.StepWork("model-final", 0, run.getExecutionEpoch(), "cp-0"));
        when(runs.beginToolStep(eq(run.getId()), eq(run.getExecutionEpoch()), eq(AgentStepType.FINALIZE),
                anyString(), eq("finalize_answer"), anyString(), eq(true)))
                .thenReturn(new KnowledgeAgentService.StepWork("tool-final", 1, run.getExecutionEpoch(), "cp-1"));
        AgentModelClient model = mock(AgentModelClient.class);
        when(model.next(anyList(), anyList(), eq(true))).thenReturn(new AgentModelClient.AgentModelTurn(null,
                List.of(new AgentModelClient.AgentToolCall("call-final", "finalize_answer", "{}")), "tool_calls",
                new AgentModelClient.AgentUsage(10, 2, 12)));
        AgentRuntime runtime = new AgentRuntime(new AppProperties(), runs, model, mock(AgentSkillRegistry.class),
                toolRegistry, mock(AgentMetrics.class), mapper);

        runtime.execute(new KnowledgeAgentService.RunWork(run.getId(), "owner", "question", false, run.getExecutionEpoch(), false));

        var definitions = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(model).next(anyList(), definitions.capture(), eq(true));
        assertThat((List<AgentModelClient.AgentToolDefinition>) definitions.getValue())
                .extracting(AgentModelClient.AgentToolDefinition::name).containsExactly("finalize_answer");
        verify(lookup, never()).execute(any(), any());
        verify(runs).completeAgentStep(eq(run.getId()), eq(run.getExecutionEpoch()), eq("tool-final"),
                anyString(), anyString(), anyLong(), any(), any(), any());
    }

    @Test
    void executesMultipleToolsAndReturnsInvalidArgumentsAsAnObservation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentTool lookup = tool(mapper, "lookup", true, false);
        AgentTool finalize = tool(mapper, "finalize_answer", false, true);
        McpReadOnlyToolProvider mcp = mock(McpReadOnlyToolProvider.class);
        when(mcp.tools()).thenReturn(List.of());
        AgentToolRegistry toolRegistry = new AgentToolRegistry(List.of(lookup, finalize), mcp);
        AgentSkill skill = new AgentSkill("knowledge-qa", "v1", "Knowledge QA", "", List.of(), "Use evidence",
                List.of("lookup", "finalize_answer"), false);
        String skillSnapshot = mapper.writeValueAsString(skill);
        KnowledgeRun run = new KnowledgeRun("owner", "question", "model", AgentScopeType.CURRENT_DOCUMENT, "Asia/Shanghai",
                skill.id(), skill.version(), skillSnapshot, Hashing.sha256(skillSnapshot), 5, 4, 4);
        run.queue(); run.start();
        KnowledgeRunDocument document = new KnowledgeRunDocument(run.getId(), "task", "document", "index-v1",
                "{\"title\":\"Note\",\"occurredAt\":\"2026-01-01T00:00:00Z\",\"sceneType\":\"MEETING\",\"tags\":[],\"transcriptVersion\":1}");
        AgentState state = AgentState.initial(AgentPhase.MODEL_DECISION, skill.id(), skill.version(), Hashing.sha256(skillSnapshot),
                        List.of(AgentModelClient.AgentMessage.system("system"), AgentModelClient.AgentMessage.user("question")))
                .withFrozenContext("model", "system", skillSnapshot,
                        List.of(new AgentState.DocumentSnapshot("task", "document", "index-v1", document.getMetadataSnapshot())),
                        5, 4, 4, 120_000);

        KnowledgeAgentService runs = mock(KnowledgeAgentService.class);
        when(runs.ownedRun("owner", run.getId())).thenReturn(run);
        when(runs.loadCurrentState(run.getId(), run.getExecutionEpoch())).thenReturn(state);
        when(runs.runDocuments(run.getId())).thenReturn(List.of(document));
        when(runs.storedSources(run.getId())).thenReturn(List.of());
        when(runs.beginModelStep(eq(run.getId()), eq(run.getExecutionEpoch()), anyString()))
                .thenReturn(new KnowledgeAgentService.StepWork("model-1", 0, run.getExecutionEpoch(), "cp-0"),
                        new KnowledgeAgentService.StepWork("model-2", 4, run.getExecutionEpoch(), "cp-3"));
        when(runs.beginToolStep(eq(run.getId()), eq(run.getExecutionEpoch()), any(), anyString(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new KnowledgeAgentService.StepWork("tool-1", 1, run.getExecutionEpoch(), "cp-1"),
                        new KnowledgeAgentService.StepWork("tool-invalid", 2, run.getExecutionEpoch(), "cp-2"),
                        new KnowledgeAgentService.StepWork("tool-final", 5, run.getExecutionEpoch(), "cp-4"));

        AgentModelClient model = mock(AgentModelClient.class);
        when(model.next(anyList(), anyList(), eq(true))).thenReturn(
                new AgentModelClient.AgentModelTurn(null, List.of(
                        new AgentModelClient.AgentToolCall("call-1", "lookup", "{\"query\":\"risk\"}"),
                        new AgentModelClient.AgentToolCall("call-2", "lookup", "{}")), "tool_calls",
                        new AgentModelClient.AgentUsage(10, 4, 14)),
                new AgentModelClient.AgentModelTurn(null, List.of(
                        new AgentModelClient.AgentToolCall("call-3", "finalize_answer", "{}")), "tool_calls",
                        new AgentModelClient.AgentUsage(20, 3, 23)));
        AgentRuntime runtime = new AgentRuntime(new AppProperties(), runs, model, mock(AgentSkillRegistry.class),
                toolRegistry, mock(AgentMetrics.class), mapper);

        runtime.execute(new KnowledgeAgentService.RunWork(run.getId(), "owner", "question", false, run.getExecutionEpoch(), false));

        verify(model, times(2)).next(anyList(), anyList(), eq(true));
        verify(lookup, times(1)).execute(any(), any());
        verify(runs).failObservedStep(eq(run.getId()), eq(run.getExecutionEpoch()), eq("tool-invalid"),
                eq("INVALID_TOOL_ARGUMENTS"), anyString(), anyLong(), any(), any(), eq(true));
        verify(runs).completeAgentStep(eq(run.getId()), eq(run.getExecutionEpoch()), eq("tool-final"),
                anyString(), anyString(), anyLong(), any(), any(), any());
    }

    private AgentTool tool(ObjectMapper mapper, String name, boolean queryRequired, boolean terminal) throws Exception {
        AgentTool tool = mock(AgentTool.class);
        JsonNode schema = mapper.readTree(queryRequired
                ? "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"}},\"required\":[\"query\"],\"additionalProperties\":false}"
                : "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
        when(tool.definition()).thenReturn(new AgentModelClient.AgentToolDefinition(name, name, schema));
        when(tool.definition(any())).thenReturn(new AgentModelClient.AgentToolDefinition(name, name, schema));
        when(tool.available(any())).thenReturn(true);
        AgentTool.ToolResult result = terminal
                ? AgentTool.ToolResult.terminal(mapper.readTree("{\"answer\":\"done\"}"), "final answer")
                : AgentTool.ToolResult.value(mapper.readTree("{\"results\":[\"risk\"]}"), "lookup result");
        when(tool.execute(any(), any())).thenReturn(result);
        return tool;
    }
}
