package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.KnowledgeRun;
import com.voicenote.repository.KnowledgeRunEvidenceRepository;
import com.voicenote.repository.KnowledgeRunRepository;
import com.voicenote.web.ApiException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class KnowledgeAgentServiceTest {
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
