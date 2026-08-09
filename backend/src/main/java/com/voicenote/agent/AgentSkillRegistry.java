package com.voicenote.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class AgentSkillRegistry {
    private static final List<String> BUILT_INS = List.of("knowledge-qa.v1.json", "interview-retro.v1.json", "meeting-summary.v1.json");
    private final ObjectMapper mapper;
    private Map<String, AgentSkill> skills = Map.of();

    public AgentSkillRegistry(ObjectMapper mapper) { this.mapper = mapper; }

    @PostConstruct
    void load() {
        Map<String, AgentSkill> loaded = new LinkedHashMap<>();
        try {
            for (String name : BUILT_INS) {
                AgentSkill skill = mapper.readValue(new ClassPathResource("agent-skills/" + name).getInputStream(), AgentSkill.class);
                if (skill.id() == null || skill.id().isBlank() || skill.version() == null || skill.allowedTools() == null) throw new IllegalStateException("Invalid agent skill " + name);
                if (loaded.put(skill.id(), skill) != null) throw new IllegalStateException("Duplicate agent skill " + skill.id());
            }
        } catch (Exception exception) { throw new IllegalStateException("Cannot load built-in agent skills", exception); }
        skills = Map.copyOf(loaded);
    }

    public AgentSkill require(String id) {
        AgentSkill skill = skills.get(id);
        if (skill == null) throw new IllegalArgumentException("Unknown agent skill: " + id);
        return skill;
    }
    public AgentSkill fallback() { return require("knowledge-qa"); }
    public List<AgentSkill> all() { return List.copyOf(skills.values()); }
}
