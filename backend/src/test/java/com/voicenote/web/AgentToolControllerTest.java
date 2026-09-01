package com.voicenote.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.*;
import com.voicenote.domain.*;
import com.voicenote.provider.AgentModelClient;
import com.voicenote.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentToolControllerTest {
    @Test
    void returnsCatalogMetadataAndEffectiveSkillAccess() {
        ObjectMapper mapper = new ObjectMapper();
        AgentTool tool = new AgentTool() {
            @Override public AgentModelClient.AgentToolDefinition definition() {
                return new AgentModelClient.AgentToolDefinition("knowledge_search", "Search authorized knowledge", mapper.createObjectNode().put("type", "object"));
            }
            @Override public ToolResult execute(AgentExecutionContext context, JsonNode arguments) {
                return ToolResult.value(mapper.createObjectNode(), "searched");
            }
        };
        AgentToolRegistry tools = mock(AgentToolRegistry.class);
        AgentSkillRegistry skills = mock(AgentSkillRegistry.class);
        McpReadOnlyToolProvider mcp = mock(McpReadOnlyToolProvider.class);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(new UserPrincipal("owner", "user@example.com"));
        AgentSkill skill = new AgentSkill("private", "v1", "私人 Skill", "", List.of(), "", List.of("knowledge_search"), false,
                "version", SkillSource.USER, SkillInvocationPolicy.MANUAL_ONLY, List.of(SceneType.values()), List.of(AgentScopeType.values()),
                List.of(SkillBlockType.SUMMARY), List.of(), null, List.of());
        when(tools.all()).thenReturn(List.of(tool));
        when(tools.allowed(skill, false)).thenReturn(List.of(tool));
        when(tools.userGrantable("knowledge_search")).thenReturn(true);
        when(skills.require("owner", "private")).thenReturn(skill);

        var result = new AgentToolController(tools, skills, mcp).list("private", authentication);

        assertThat(result.skillId()).isEqualTo("private");
        assertThat(result.tools()).singleElement().satisfies(value -> {
            assertThat(value.name()).isEqualTo("knowledge_search");
            assertThat(value.displayName()).isEqualTo("知识检索");
            assertThat(value.uiDescription()).contains("Dense + BM25");
            assertThat(value.category()).isEqualTo(AgentToolController.ToolCategory.RETRIEVAL);
            assertThat(value.accessMode()).isEqualTo(AgentToolController.ToolAccessMode.SKILL_CONFIGURABLE);
            assertThat(value.availabilityHint()).contains("活动知识索引");
            assertThat(value.source()).isEqualTo(AgentTool.Source.LOCAL);
            assertThat(value.enabledForSkill()).isTrue();
            assertThat(value.userGrantable()).isTrue();
        });
        verify(skills).require("owner", "private");
    }

    @Test
    void describesPlatformMemoryAndDeploymentManagedMcpTools() {
        ObjectMapper mapper = new ObjectMapper();
        AgentTool memory = tool(mapper, "user_memory_search", AgentTool.Source.LOCAL);
        AgentTool mcpTool = tool(mapper, "mcp.calendar.lookup", AgentTool.Source.MCP);
        AgentToolRegistry tools = mock(AgentToolRegistry.class);
        AgentSkillRegistry skills = mock(AgentSkillRegistry.class);
        McpReadOnlyToolProvider mcp = mock(McpReadOnlyToolProvider.class);
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(new UserPrincipal("owner", "user@example.com"));
        when(tools.all()).thenReturn(List.of(memory, mcpTool));

        var result = new AgentToolController(tools, skills, mcp).list(null, authentication);

        assertThat(result.tools()).extracting(AgentToolController.ToolView::displayName)
                .containsExactly("确认记忆检索", "mcp.calendar.lookup");
        assertThat(result.tools().get(0).accessMode()).isEqualTo(AgentToolController.ToolAccessMode.PLATFORM_CONDITIONAL);
        assertThat(result.tools().get(0).availabilityHint()).contains("当前会话启用记忆");
        assertThat(result.tools().get(1).category()).isEqualTo(AgentToolController.ToolCategory.EXTENSION);
        assertThat(result.tools().get(1).accessMode()).isEqualTo(AgentToolController.ToolAccessMode.DEPLOYMENT_MANAGED);
        assertThat(result.tools().get(1).uiDescription()).contains("MCP");
    }

    private static AgentTool tool(ObjectMapper mapper, String name, AgentTool.Source source) {
        return new AgentTool() {
            @Override public AgentModelClient.AgentToolDefinition definition() {
                return new AgentModelClient.AgentToolDefinition(name, name, mapper.createObjectNode().put("type", "object"));
            }
            @Override public Source source() { return source; }
            @Override public ToolResult execute(AgentExecutionContext context, JsonNode arguments) {
                return ToolResult.value(mapper.createObjectNode(), name);
            }
        };
    }
}
