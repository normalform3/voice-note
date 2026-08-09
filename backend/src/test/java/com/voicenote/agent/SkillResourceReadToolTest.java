package com.voicenote.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.tools.SkillResourceReadTool;
import com.voicenote.domain.*;
import com.voicenote.repository.SkillResourceRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SkillResourceReadToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readsOnlyResourcesFromTheFrozenVersionAndPaginates() throws Exception {
        SkillResourceRepository resources = mock(SkillResourceRepository.class);
        SkillResource stored = new SkillResource("version-a", "reference.md", SkillResourceType.REFERENCE, "参考", "核实规则", "第一段\n第二段", "hash", 0);
        when(resources.findByIdAndSkillVersionId(stored.getId(), "version-a")).thenReturn(Optional.of(stored));
        AgentSkill skill = new AgentSkill("custom", "v1", "私人 Skill", "", List.of(), "", List.of("skill_resource_read"), false,
                "version-a", SkillSource.USER, SkillInvocationPolicy.MANUAL_ONLY, List.of(SceneType.values()), List.of(AgentScopeType.values()),
                List.of(SkillBlockType.SUMMARY), List.of(new AgentSkill.ResourceDescriptor(stored.getId(), "reference.md", SkillResourceType.REFERENCE, "参考", "核实规则", 20)), null, List.of());
        AgentExecutionContext context = new AgentExecutionContext("run", "owner", AgentScopeType.CURRENT_DOCUMENT, ZoneId.of("Asia/Shanghai"),
                skill, List.of(), Instant.now().plusSeconds(10));
        SkillResourceReadTool tool = new SkillResourceReadTool(resources, mapper);

        var page = tool.execute(context, mapper.readTree("{\"resourceId\":\"" + stored.getId() + "\",\"maxBytes\":9}"));
        assertThat(page.payload().path("returnedBytes").asInt()).isLessThanOrEqualTo(9);
        assertThat(page.payload().path("hasMore").asBoolean()).isTrue();

        assertThatThrownBy(() -> tool.execute(context, mapper.readTree("{\"resourceId\":\"outside\"}")))
                .hasMessageContaining("outside the frozen Run version");
        verify(resources, never()).findByIdAndSkillVersionId("outside", "version-a");
    }
}
