package com.voicenote.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voicenote.agent.*;
import com.voicenote.domain.SkillResource;
import com.voicenote.provider.AgentModelClient;
import com.voicenote.repository.SkillResourceRepository;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class SkillResourceReadTool implements AgentTool {
    private static final int MAX_READ_BYTES = 8 * 1024;
    private final SkillResourceRepository resources;
    private final ObjectMapper mapper;
    public SkillResourceReadTool(SkillResourceRepository resources, ObjectMapper mapper) { this.resources = resources; this.mapper = mapper; }

    @Override public AgentModelClient.AgentToolDefinition definition() {
        ObjectNode schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("resourceId").put("type", "string");
        properties.putObject("offset").put("type", "integer").put("minimum", 0);
        properties.putObject("maxBytes").put("type", "integer").put("minimum", 1).put("maximum", MAX_READ_BYTES);
        schema.putArray("required").add("resourceId");
        return new AgentModelClient.AgentToolDefinition("skill_resource_read",
                "Read one declared resource from the frozen Skill version. Reads at most 8 KB and supports character offsets.", schema);
    }

    @Override public ToolResult execute(AgentExecutionContext context, JsonNode arguments) {
        String resourceId = arguments.path("resourceId").asText();
        AgentSkill.ResourceDescriptor descriptor = context.skill().resources().stream().filter(value -> value.id().equals(resourceId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Skill resource is outside the frozen Run version"));
        SkillResource resource = resources.findByIdAndSkillVersionId(resourceId, context.skill().versionId())
                .orElseThrow(() -> new IllegalArgumentException("Skill resource is unavailable for the frozen Run version"));
        int offset = arguments.path("offset").asInt(0); int maxBytes = arguments.path("maxBytes").asInt(MAX_READ_BYTES);
        String content = resource.getMarkdownContent();
        if (offset > content.length()) throw new IllegalArgumentException("Skill resource offset exceeds content length");
        String remaining = content.substring(offset); String page = AgentOutputLimits.truncateUtf8(remaining, Math.min(MAX_READ_BYTES, maxBytes));
        int nextOffset = offset + page.length();
        ObjectNode result = mapper.createObjectNode().put("resourceId", descriptor.id()).put("name", descriptor.name())
                .put("type", descriptor.type().name()).put("purpose", descriptor.purpose()).put("content", page)
                .put("offset", offset).put("nextOffset", nextOffset).put("hasMore", nextOffset < content.length())
                .put("returnedBytes", page.getBytes(StandardCharsets.UTF_8).length);
        return ToolResult.value(result, "读取 Skill 资源：" + descriptor.name());
    }
}
