package com.voicenote.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.domain.*;
import com.voicenote.repository.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentSkillRegistryTest {
    @Test
    void synchronizesTheThreeVersionedBuiltInPackages() {
        SkillDefinitionRepository definitions = mock(SkillDefinitionRepository.class);
        SkillVersionRepository versions = mock(SkillVersionRepository.class);
        SkillResourceRepository resources = mock(SkillResourceRepository.class);
        when(definitions.findById(any())).thenReturn(Optional.empty());
        when(versions.findBySkillDefinitionIdAndVersionName(any(), any())).thenReturn(Optional.empty());
        when(definitions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(versions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(resources.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        new AgentSkillRegistry(new ObjectMapper(), definitions, versions, resources).synchronizeBuiltIns();

        ArgumentCaptor<SkillVersion> captured = ArgumentCaptor.forClass(SkillVersion.class);
        verify(versions, times(6)).save(captured.capture());
        assertThat(captured.getAllValues()).extracting(SkillVersion::getSkillDefinitionId).contains("knowledge-qa", "meeting-summary", "interview-retro");
        assertThat(captured.getAllValues()).extracting(SkillVersion::getVersionName).containsOnly("v3");
        verify(resources, times(9)).save(any());
    }

    @Test
    void rejectsBuiltInContentChangesWithoutAVersionBump() {
        SkillDefinitionRepository definitions = mock(SkillDefinitionRepository.class);
        SkillVersionRepository versions = mock(SkillVersionRepository.class);
        SkillResourceRepository resources = mock(SkillResourceRepository.class);
        SkillDefinition definition = SkillDefinition.builtIn("knowledge-qa", "知识问答", "", "[]", "[]");
        SkillVersion mismatched = new SkillVersion("knowledge-qa", 3, "v3", "changed", "[]", "[]", "[]", "[]", null, "different-hash");
        when(definitions.findById("knowledge-qa")).thenReturn(Optional.of(definition));
        when(versions.findBySkillDefinitionIdAndVersionName("knowledge-qa", "v3")).thenReturn(Optional.of(mismatched));

        assertThatThrownBy(() -> new AgentSkillRegistry(new ObjectMapper(), definitions, versions, resources).synchronizeBuiltIns())
                .hasMessageContaining("Cannot synchronize built-in Agent Skills").hasRootCauseMessage("Built-in Skill knowledge-qa changed without a version bump");
    }
}
