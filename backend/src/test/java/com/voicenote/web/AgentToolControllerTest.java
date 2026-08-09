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
        Authentication authentication = mock(Authentication.class);
        when(authentication.getPrincipal()).thenReturn(new UserPrincipal("owner", "user@example.com"));
        AgentSkill skill = new AgentSkill("private", "v1", "私人 Skill", "", List.of(), "", List.of("knowledge_search"), false,
                "version", SkillSource.USER, SkillInvocationPolicy.MANUAL_ONLY, List.of(SceneType.values()), List.of(AgentScopeType.values()),
                List.of(SkillBlockType.SUMMARY), List.of(), null, List.of());
        when(tools.all()).thenReturn(List.of(tool));
        when(tools.allowed(skill, false)).thenReturn(List.of(tool));
        when(tools.userGrantable("knowledge_search")).thenReturn(true);
        when(skills.require("owner", "private")).thenReturn(skill);

        var result = new AgentToolController(tools, skills).list("private", authentication);

        assertThat(result.skillId()).isEqualTo("private");
        assertThat(result.tools()).singleElement().satisfies(value -> {
            assertThat(value.name()).isEqualTo("knowledge_search");
            assertThat(value.displayName()).isEqualTo("知识检索");
            assertThat(value.source()).isEqualTo(AgentTool.Source.LOCAL);
            assertThat(value.enabledForSkill()).isTrue();
            assertThat(value.userGrantable()).isTrue();
        });
        verify(skills).require("owner", "private");
    }
}
