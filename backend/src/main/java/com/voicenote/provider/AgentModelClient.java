package com.voicenote.provider;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public interface AgentModelClient {
    AgentModelTurn next(List<AgentMessage> messages, List<AgentToolDefinition> tools, boolean requireTool);

    record AgentMessage(String role, String content, String toolCallId, List<AgentToolCall> toolCalls) {
        public static AgentMessage system(String content) { return new AgentMessage("system", content, null, List.of()); }
        public static AgentMessage user(String content) { return new AgentMessage("user", content, null, List.of()); }
        public static AgentMessage assistant(String content, List<AgentToolCall> calls) { return new AgentMessage("assistant", content, null, calls == null ? List.of() : calls); }
        public static AgentMessage tool(String callId, String content) { return new AgentMessage("tool", content, callId, List.of()); }
    }
    record AgentToolCall(String id, String name, String arguments) { }
    record AgentToolDefinition(String name, String description, JsonNode parameters) { }
    record AgentUsage(Integer inputTokens, Integer outputTokens, Integer totalTokens) { }
    record AgentModelTurn(String content, List<AgentToolCall> toolCalls, String finishReason, AgentUsage usage) { }
}
