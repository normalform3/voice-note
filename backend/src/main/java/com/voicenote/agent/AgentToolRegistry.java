package com.voicenote.agent;

import com.voicenote.domain.SkillSource;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class AgentToolRegistry {
    private static final Set<String> USER_GRANTABLE = Set.of("document_list", "document_overview", "knowledge_search",
            "transcript_context", "skill_resource_read", "finalize_answer");
    private final Map<String, AgentTool> tools;
    public AgentToolRegistry(List<AgentTool> values, McpReadOnlyToolProvider mcp) {
        Map<String, AgentTool> registered = new LinkedHashMap<>();
        List<AgentTool> all = new ArrayList<>(values); all.addAll(mcp.tools());
        for (AgentTool value : all) {
            String name = value.definition().name();
            if (registered.put(name, value) != null) throw new IllegalStateException("Duplicate Agent tool: " + name);
        }
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(registered));
    }
    public AgentTool require(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("Unknown Agent tool: " + name);
        return tool;
    }
    public List<AgentTool> all() { return List.copyOf(tools.values()); }
    public boolean userGrantable(String name) { return USER_GRANTABLE.contains(name); }
    public List<AgentTool> allowed(AgentSkill skill, boolean finalOnly) {
        LinkedHashSet<String> names = new LinkedHashSet<>(skill.allowedTools());
        if (skill.source() == SkillSource.USER) names.retainAll(USER_GRANTABLE);
        else tools.forEach((name, tool) -> { if (tool.allowedSkillIds().contains(skill.id())) names.add(name); });
        return names.stream().filter(name -> !finalOnly || name.equals("finalize_answer")).map(this::require).toList();
    }
}
