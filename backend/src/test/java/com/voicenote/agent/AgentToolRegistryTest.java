package com.voicenote.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.*;
import com.voicenote.provider.AgentModelClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolRegistryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void exposesRegisteredToolsAndKeepsMcpOutsidePrivateSkills() {
        AgentTool local = tool("knowledge_search", AgentTool.Source.LOCAL, Set.of());
        AgentTool mcpTool = tool("mcp.crm.lookup", AgentTool.Source.MCP, Set.of("meeting-summary"));
        McpReadOnlyToolProvider mcp = mock(McpReadOnlyToolProvider.class);
        when(mcp.tools()).thenReturn(List.of(mcpTool));
        AgentToolRegistry registry = new AgentToolRegistry(List.of(local), mcp);
        AgentSkill privateSkill = skill("private", SkillSource.USER, List.of("knowledge_search", "mcp.crm.lookup"));
        AgentSkill builtIn = skill("meeting-summary", SkillSource.BUILTIN, List.of("knowledge_search"));

        assertThat(registry.all()).extracting(value -> value.definition().name())
                .containsExactly("knowledge_search", "mcp.crm.lookup");
        assertThat(registry.allowed(privateSkill, false)).extracting(value -> value.definition().name())
                .containsExactly("knowledge_search");
        assertThat(registry.allowed(builtIn, false)).extracting(value -> value.definition().name())
                .containsExactly("knowledge_search", "mcp.crm.lookup");
        assertThat(registry.userGrantable("knowledge_search")).isTrue();
        assertThat(registry.userGrantable("mcp.crm.lookup")).isFalse();
    }

    private AgentSkill skill(String id, SkillSource source, List<String> tools) {
        return new AgentSkill(id, "v1", id, "", List.of(), "", tools, false, "version", source,
                SkillInvocationPolicy.MANUAL_ONLY, List.of(SceneType.values()), List.of(AgentScopeType.values()),
                List.of(SkillBlockType.SUMMARY), List.of(), null, List.of());
    }

    private AgentTool tool(String name, AgentTool.Source source, Set<String> allowedSkills) {
        return new AgentTool() {
            @Override public AgentModelClient.AgentToolDefinition definition() {
                return new AgentModelClient.AgentToolDefinition(name, name, mapper.createObjectNode().put("type", "object"));
            }
            @Override public Source source() { return source; }
            @Override public Set<String> allowedSkillIds() { return allowedSkills; }
            @Override public ToolResult execute(AgentExecutionContext context, JsonNode arguments) {
                return ToolResult.value(mapper.createObjectNode(), name);
            }
        };
    }
}
