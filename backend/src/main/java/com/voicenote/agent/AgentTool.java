package com.voicenote.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.voicenote.provider.AgentModelClient;
import java.util.Set;

public interface AgentTool {
    enum Source { LOCAL, MCP }

    AgentModelClient.AgentToolDefinition definition();
    default AgentModelClient.AgentToolDefinition definition(AgentExecutionContext context) { return definition(); }
    ToolResult execute(AgentExecutionContext context, JsonNode arguments);
    default Source source() { return Source.LOCAL; }
    default boolean dynamicParameters() { return false; }
    default Set<String> allowedSkillIds() { return Set.of(); }
    record ToolResult(JsonNode payload, String summary, boolean terminal) {
        public static ToolResult value(JsonNode payload, String summary) { return new ToolResult(payload, summary, false); }
        public static ToolResult terminal(JsonNode payload, String summary) { return new ToolResult(payload, summary, true); }
    }
}
