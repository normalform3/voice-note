package com.voicenote.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.voicenote.agent.AgentSkill;
import com.voicenote.agent.AgentSkillRegistry;
import com.voicenote.agent.AgentTool;
import com.voicenote.agent.AgentToolRegistry;
import com.voicenote.agent.McpReadOnlyToolProvider;
import com.voicenote.domain.SkillSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/agent-tools")
public class AgentToolController {
    private static final Map<String, String> DISPLAY_NAMES = Map.of(
            "document_list", "文档筛选",
            "document_overview", "文档概览",
            "knowledge_search", "知识检索",
            "transcript_context", "原文上下文",
            "skill_resource_read", "Skill 资源读取",
            "finalize_answer", "提交最终结果"
    );

    private final AgentToolRegistry tools;
    private final AgentSkillRegistry skills;
    private final McpReadOnlyToolProvider mcp;

    public AgentToolController(AgentToolRegistry tools, AgentSkillRegistry skills, McpReadOnlyToolProvider mcp) {
        this.tools = tools;
        this.skills = skills;
        this.mcp = mcp;
    }

    @GetMapping
    ToolCatalogView list(@RequestParam(required = false) String skillId, Authentication authentication) {
        String ownerId = CurrentUser.require(authentication).id();
        AgentSkill selected = skillId == null || skillId.isBlank() ? null : requireSkill(ownerId, skillId);
        Set<String> enabled = selected == null ? Set.of() : tools.allowed(selected, false).stream()
                .map(value -> value.definition().name()).collect(java.util.stream.Collectors.toSet());
        return new ToolCatalogView(selected == null ? null : selected.id(), tools.all().stream()
                .map(tool -> view(tool, selected, enabled)).toList());
    }

    @GetMapping("/mcp-status")
    List<McpReadOnlyToolProvider.ServerStatus> mcpStatus(Authentication authentication) {
        CurrentUser.require(authentication);
        return mcp.statuses();
    }

    private ToolView view(AgentTool tool, AgentSkill skill, Set<String> enabled) {
        var definition = tool.definition();
        Boolean enabledForSkill = skill == null ? null : enabled.contains(definition.name());
        String disabledReason = null;
        if (Boolean.FALSE.equals(enabledForSkill)) {
            disabledReason = skill.source() == SkillSource.USER && tool.source() == AgentTool.Source.MCP
                    ? "PERSONAL_SKILL_LOCAL_ONLY" : "NOT_GRANTED_BY_SKILL";
        }
        return new ToolView(definition.name(), DISPLAY_NAMES.getOrDefault(definition.name(), definition.name()), definition.description(),
                tool.source(), tools.userGrantable(definition.name()), enabledForSkill, disabledReason,
                definition.parameters(), tool.dynamicParameters());
    }

    private AgentSkill requireSkill(String ownerId, String skillId) {
        try { return skills.require(ownerId, skillId); }
        catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "Skill was not found or is not available");
        }
    }

    public record ToolCatalogView(String skillId, List<ToolView> tools) { }
    public record ToolView(String name, String displayName, String description, AgentTool.Source source,
                           boolean userGrantable, Boolean enabledForSkill, String disabledReason,
                           JsonNode parameters, boolean dynamicParameters) { }
}
