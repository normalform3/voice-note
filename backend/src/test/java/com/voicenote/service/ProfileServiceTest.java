package com.voicenote.service;

import com.voicenote.domain.*;
import com.voicenote.repository.*;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ProfileServiceTest {
    @Test
    void usesOwnerScopedStatisticsAndExcludesLegacyRunsAndArchivedSkills() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class);
        KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        KnowledgeRunRepository runs = mock(KnowledgeRunRepository.class);
        SkillDefinitionRepository skills = mock(SkillDefinitionRepository.class);
        UserAccount user = new UserAccount("user@example.com", "hash");
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(tasks.countByOwnerId(user.getId())).thenReturn(4L);
        when(documents.countByOwnerIdAndStatus(user.getId(), KnowledgeDocumentStatus.READY)).thenReturn(3L);
        when(runs.countByOwnerIdAndSkillVersionNot(user.getId(), "legacy-v1")).thenReturn(7L);
        when(skills.countByOwnerIdAndSourceAndStatusNot(user.getId(), SkillSource.USER, SkillStatus.ARCHIVED)).thenReturn(2L);

        var result = new ProfileService(users, tasks, documents, runs, skills).get(user.getId());

        assertThat(result.account()).isEqualTo("user@example.com");
        assertThat(result.statistics().recordingCount()).isEqualTo(4);
        assertThat(result.statistics().indexedDocumentCount()).isEqualTo(3);
        assertThat(result.statistics().agentRunCount()).isEqualTo(7);
        assertThat(result.statistics().customSkillCount()).isEqualTo(2);
    }
}
