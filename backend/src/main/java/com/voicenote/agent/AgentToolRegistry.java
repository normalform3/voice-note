package com.voicenote.agent;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class AgentToolRegistry {
    private final Map<String, AgentTool> tools;
    public AgentToolRegistry(List<AgentTool> values, McpReadOnlyToolProvider mcp) {
        Map<String, AgentTool> registered = new LinkedHashMap<>();
        List<AgentTool> all = new ArrayList<>(values); all.addAll(mcp.tools());
        for (AgentTool value : all) {
            String name = value.definition().name();
            if (registered.put(name, value) != null) throw new IllegalStateException("Duplicate Agent tool: " + name);
        }
        this.tools = Map.copyOf(registered);
    }
    public AgentTool require(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) throw new IllegalArgumentException("Unknown Agent tool: " + name);
        return tool;
    }
    public List<AgentTool> allowed(AgentSkill skill, boolean finalOnly) {
        LinkedHashSet<String> names = new LinkedHashSet<>(skill.allowedTools());
        tools.forEach((name, tool) -> { if (tool.allowedSkillIds().contains(skill.id())) names.add(name); });
        return names.stream().filter(name -> !finalOnly || name.equals("finalize_answer")).map(this::require).toList();
    }
}
