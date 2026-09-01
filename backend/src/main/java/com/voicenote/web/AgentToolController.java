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
    private static final Map<String, ToolPresentation> PRESENTATIONS = Map.ofEntries(
            Map.entry("document_list", new ToolPresentation("文档筛选",
                    "筛选本轮已授权的听记，可按时间、场景和标签缩小范围。", ToolCategory.SCOPE,
                    ToolAccessMode.SKILL_CONFIGURABLE, "只会返回本轮授权范围内的文档。")),
            Map.entry("document_overview", new ToolPresentation("文档概览",
                    "读取正式文档或活动索引中的结构化概览，用于多文档覆盖和初步比较。", ToolCategory.SCOPE,
                    ToolAccessMode.SKILL_CONFIGURABLE, "范围内至少一份文档已生成正式文档或知识索引时可用。")),
            Map.entry("knowledge_search", new ToolPresentation("知识检索",
                    "在已入库文档中执行 Dense + BM25 混合检索，并返回可引用的原文证据。", ToolCategory.RETRIEVAL,
                    ToolAccessMode.SKILL_CONFIGURABLE, "仅对已有活动知识索引的文档可用。")),
            Map.entry("transcript_context", new ToolPresentation("原文上下文",
                    "搜索、相邻回读或在预算内读取原始转写，并解析说话人名称和角色。", ToolCategory.RETRIEVAL,
                    ToolAccessMode.SKILL_CONFIGURABLE, "当前文档无需知识索引；全文读取仍受上下文和输出预算限制。")),
            Map.entry("skill_resource_read", new ToolPresentation("Skill 资源读取",
                    "按需读取当前冻结 Skill 版本声明的参考资料、模板或示例。", ToolCategory.CONTEXT,
                    ToolAccessMode.SKILL_CONFIGURABLE, "Skill 声明资源后使用，单次最多读取 8 KB。")),
            Map.entry("user_memory_search", new ToolPresentation("确认记忆检索",
                    "检索当前用户已明确确认的长期记忆，为连续对话补充偏好和背景。", ToolCategory.CONTEXT,
                    ToolAccessMode.PLATFORM_CONDITIONAL, "仅当部署开启长期记忆且当前会话启用记忆时可用。")),
            Map.entry("finalize_answer", new ToolPresentation("提交最终结果",
                    "校验结构化结果和本轮 sourceRef，提交最终可审计答案。", ToolCategory.CONTROL,
                    ToolAccessMode.PLATFORM_REQUIRED, "所有 Agent 回答必须通过此工具完成，不能从 Skill 中移除。"))
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
        ToolPresentation presentation = presentation(tool);
        Boolean enabledForSkill = skill == null ? null : enabled.contains(definition.name());
        String disabledReason = null;
        if (Boolean.FALSE.equals(enabledForSkill)) {
            disabledReason = skill.source() == SkillSource.USER && tool.source() == AgentTool.Source.MCP
                    ? "PERSONAL_SKILL_LOCAL_ONLY" : "NOT_GRANTED_BY_SKILL";
        }
        return new ToolView(definition.name(), presentation.displayName(), definition.description(), presentation.uiDescription(),
                presentation.category(), presentation.accessMode(), presentation.availabilityHint(), tool.source(),
                tools.userGrantable(definition.name()), enabledForSkill, disabledReason, definition.parameters(), tool.dynamicParameters());
    }

    private ToolPresentation presentation(AgentTool tool) {
        var definition = tool.definition();
        ToolPresentation configured = PRESENTATIONS.get(definition.name());
        if (configured != null) return configured;
        if (tool.source() == AgentTool.Source.MCP) {
            return new ToolPresentation(definition.name(), "通过部署批准的只读 MCP 服务读取外部信息。",
                    ToolCategory.EXTENSION, ToolAccessMode.DEPLOYMENT_MANAGED,
                    "需要服务已连接、工具通过只读白名单，并由内置 Skill 获得部署授权。");
        }
        return new ToolPresentation(definition.name(), definition.description(), ToolCategory.EXTENSION,
                tools.userGrantable(definition.name()) ? ToolAccessMode.SKILL_CONFIGURABLE : ToolAccessMode.PLATFORM_CONDITIONAL,
                "实际可用性由当前运行范围和部署能力决定。");
    }

    private AgentSkill requireSkill(String ownerId, String skillId) {
        try { return skills.require(ownerId, skillId); }
        catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "Skill was not found or is not available");
        }
    }

    public record ToolCatalogView(String skillId, List<ToolView> tools) { }
    public enum ToolCategory { SCOPE, RETRIEVAL, CONTEXT, CONTROL, EXTENSION }
    public enum ToolAccessMode { SKILL_CONFIGURABLE, PLATFORM_REQUIRED, PLATFORM_CONDITIONAL, DEPLOYMENT_MANAGED }
    private record ToolPresentation(String displayName, String uiDescription, ToolCategory category,
                                    ToolAccessMode accessMode, String availabilityHint) { }
    public record ToolView(String name, String displayName, String description, String uiDescription,
                           ToolCategory category, ToolAccessMode accessMode, String availabilityHint,
                           AgentTool.Source source, boolean userGrantable, Boolean enabledForSkill,
                           String disabledReason, JsonNode parameters, boolean dynamicParameters) { }
}
