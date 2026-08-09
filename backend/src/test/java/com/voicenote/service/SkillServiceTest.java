package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.*;
import com.voicenote.provider.AgentModelClient;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SkillServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void intersectsUserRequestedToolsWithTheReadOnlyGrantSet() throws Exception {
        Fixture fixture = new Fixture();
        SkillDefinition definition = SkillDefinition.user("owner", "客户访谈", "提炼需求", json(List.of(SceneType.OTHER)), json(List.of(AgentScopeType.CURRENT_DOCUMENT)));
        SkillVersion draft = new SkillVersion(definition.getId(), 1, "v1", "旧指令", "[]", json(List.of(SkillBlockType.SUMMARY)), "[]", "[]", null, "old");
        definition.setDraftVersion(draft.getId()); fixture.stub(definition, draft);

        fixture.service.updateDraft("owner", definition.getId(), new SkillService.DraftCommand("客户访谈", "提炼需求",
                List.of(SceneType.OTHER), List.of(AgentScopeType.CURRENT_DOCUMENT), "只依据原文整理需求",
                List.of("knowledge_search", "mcp.private.write", "shell", "finalize_answer"), List.of(SkillBlockType.SUMMARY),
                List.of("提炼客户需求"), List.of("发送跟进邮件"), null, List.of()));

        List<String> storedTools = mapper.readValue(draft.getAllowedTools(), mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        assertThat(storedTools).containsExactlyInAnyOrder("knowledge_search", "finalize_answer");
        assertThat(storedTools).noneMatch(value -> value.startsWith("mcp") || value.equals("shell"));
    }

    @Test
    void isolatesPrivateSkillsByOwnerAndRequiresPreviewBeforeAutoRouting() {
        Fixture fixture = new Fixture();
        SkillDefinition definition = SkillDefinition.user("owner", "客户访谈", "提炼需求", json(List.of(SceneType.OTHER)), json(List.of(AgentScopeType.CURRENT_DOCUMENT)));
        SkillVersion published = new SkillVersion(definition.getId(), 1, "v1", "指令", json(List.of("finalize_answer")), json(List.of(SkillBlockType.SUMMARY)),
                json(List.of("问题1", "问题2", "问题3")), json(List.of("反例1", "反例2", "反例3")), null, "hash");
        published.publish(); definition.publish(published.getId()); fixture.stub(definition, published);

        assertThatThrownBy(() -> fixture.service.get("other-owner", definition.getId())).isInstanceOf(ApiException.class).hasMessageContaining("not found");
        assertThatThrownBy(() -> fixture.service.autoEnable("owner", definition.getId())).isInstanceOf(ApiException.class).hasMessageContaining("preview");
        assertThat(definition.getInvocationPolicy()).isEqualTo(SkillInvocationPolicy.MANUAL_ONLY);
    }

    @Test
    void permanentlyDeletesOwnedSkillVersionsAndResourcesUnlessAnActiveRunUsesThem() {
        Fixture fixture = new Fixture();
        SkillDefinition definition = SkillDefinition.user("owner", "客户访谈", "提炼需求", json(List.of(SceneType.OTHER)), json(List.of(AgentScopeType.CURRENT_DOCUMENT)));
        SkillVersion version = new SkillVersion(definition.getId(), 1, "v1", "指令", json(List.of("finalize_answer")),
                json(List.of(SkillBlockType.SUMMARY)), "[]", "[]", null, "hash");
        definition.setDraftVersion(version.getId()); fixture.stub(definition, version);

        fixture.service.delete("owner", definition.getId());

        verify(fixture.resources).deleteBySkillVersionId(version.getId());
        verify(fixture.versions).deleteAllInBatch(List.of(version));
        verify(fixture.definitions).delete(definition);

        when(fixture.runs.existsBySkillVersionIdInAndStatusIn(any(), any())).thenReturn(true);
        assertThatThrownBy(() -> fixture.service.delete("owner", definition.getId()))
                .isInstanceOf(ApiException.class).hasMessageContaining("active Agent Run");
    }

    @Test
    void refusesToDeleteBuiltInOrAnotherOwnersSkill() {
        Fixture fixture = new Fixture();
        SkillDefinition privateSkill = SkillDefinition.user("owner", "私人", "描述", json(List.of(SceneType.OTHER)), json(List.of(AgentScopeType.CURRENT_DOCUMENT)));
        when(fixture.definitions.findById(privateSkill.getId())).thenReturn(Optional.of(privateSkill));
        assertThatThrownBy(() -> fixture.service.delete("other-owner", privateSkill.getId()))
                .isInstanceOf(ApiException.class).hasMessageContaining("not found");

        SkillDefinition builtIn = SkillDefinition.builtIn("knowledge-qa", "知识问答", "描述", json(List.of(SceneType.values())), json(List.of(AgentScopeType.values())));
        when(fixture.definitions.findById(builtIn.getId())).thenReturn(Optional.of(builtIn));
        assertThatThrownBy(() -> fixture.service.delete("owner", builtIn.getId()))
                .isInstanceOf(ApiException.class).hasMessageContaining("read-only");
        verify(fixture.definitions, never()).delete(any());
    }

    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new AssertionError(exception); } }

    private final class Fixture {
        final SkillDefinitionRepository definitions = mock(SkillDefinitionRepository.class);
        final SkillVersionRepository versions = mock(SkillVersionRepository.class);
        final SkillResourceRepository resources = mock(SkillResourceRepository.class);
        final KnowledgeRunRepository runs = mock(KnowledgeRunRepository.class);
        final SkillService service = new SkillService(definitions, versions, resources, runs, mock(AgentModelClient.class), mapper);
        Fixture() {
            when(definitions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(versions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(resources.findBySkillVersionIdOrderBySortOrderAsc(any())).thenReturn(List.of());
        }
        void stub(SkillDefinition definition, SkillVersion version) {
            when(definitions.findById(definition.getId())).thenReturn(Optional.of(definition));
            when(versions.findById(version.getId())).thenReturn(Optional.of(version));
            when(versions.findBySkillDefinitionIdOrderByVersionNumberDesc(definition.getId())).thenReturn(List.of(version));
        }
    }
}
