package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.AgentSkill;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.KnowledgeRunEvidenceRepository;
import com.voicenote.repository.KnowledgeRunRepository;
import com.voicenote.web.ApiException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class KnowledgeAgentServiceTest {
    @Test
    void readsTheHistoricalSkillNameFromTheFrozenSnapshot() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AgentSkill skill = new AgentSkill("private-skill", "v3", "客户需求复盘", "", List.of(), "", List.of("finalize_answer"), false);
        KnowledgeRun run = new KnowledgeRun("owner", "question", "model", AgentScopeType.CURRENT_DOCUMENT, "Asia/Shanghai",
                skill.id(), skill.version(), mapper.writeValueAsString(skill), "hash", 4, 4, 4);
        KnowledgeAgentService service = new KnowledgeAgentService(mock(KnowledgeRunRepository.class), mock(KnowledgeRunEvidenceRepository.class),
                mock(IdempotencyService.class), mock(OutboxService.class), mapper, new AppProperties());

        assertThat(service.skillDisplayName(run)).isEqualTo("客户需求复盘");
    }

    @Test
    void rejectsEvidenceForASegmentTheAgentDidNotRead() {
        KnowledgeRunRepository runs = mock(KnowledgeRunRepository.class);
        KnowledgeRunEvidenceRepository evidence = mock(KnowledgeRunEvidenceRepository.class);
        KnowledgeRun run = new KnowledgeRun("owner", "question", "model", 4);
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        KnowledgeAgentService service = new KnowledgeAgentService(runs, evidence, mock(IdempotencyService.class), mock(OutboxService.class), new ObjectMapper(), new AppProperties());
        var readable = new KnowledgeSearchService.ReadableChunk("document", "task", "title", "chunk-a", 0, 1_000, List.of("segment-a"), "source");

        assertThatThrownBy(() -> service.complete(run.getId(), "{\"answer\":\"x\",\"findings\":[{\"evidence\":[{\"chunkId\":\"chunk-a\",\"segmentId\":\"segment-b\"}]}]}", List.of(readable)))
                .isInstanceOf(ApiException.class).hasMessageContaining("did not read");
        verify(evidence, never()).save(any());
    }
}
