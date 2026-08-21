package com.voicenote.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voicenote.agent.AgentEvidenceLedger;
import com.voicenote.agent.AgentSkill;
import com.voicenote.agent.AgentSkillRegistry;
import com.voicenote.config.AppProperties;
import com.voicenote.domain.*;
import com.voicenote.repository.*;
import com.voicenote.web.ApiException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void freezesAFormalOverviewForAnUnindexedCurrentDocument() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules(); AppProperties properties = new AppProperties(); properties.getAgent().setEnabled(true);
        KnowledgeRunRepository runs = mock(KnowledgeRunRepository.class); KnowledgeRunDocumentRepository runDocuments = mock(KnowledgeRunDocumentRepository.class);
        TranscriptionTaskRepository tasks = mock(TranscriptionTaskRepository.class); KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
        OrganizedDocumentRepository organizedDocuments = mock(OrganizedDocumentRepository.class); OrganizedDocumentBlockRepository blocks = mock(OrganizedDocumentBlockRepository.class);
        IdempotencyService idempotency = mock(IdempotencyService.class); AgentSkillRegistry skills = mock(AgentSkillRegistry.class);
        TranscriptionTask task = new TranscriptionTask("owner", "audio", "a".repeat(64), "pipeline"); task.transcriptPersisted();
        OrganizedDocument organized = new OrganizedDocument("owner", task.getId(), task.getTranscriptVersion(), "正式标题"); organized.ready("正式标题", "摘要", "LLM", "{}", "正文");
        OrganizedDocumentBlock topic = new OrganizedDocumentBlock(organized.getId(), 0, OrganizedBlockType.TOPIC, null, "主题", "主题摘要", "[\"S1\"]",
                0, 1_000, "[\"segment-1\"]", "[{\"segmentId\":\"segment-1\",\"speakerId\":\"S1\",\"startMs\":0,\"endMs\":1000,\"text\":\"原文证据\"}]", "主题正文");
        AgentSkill skill = new AgentSkill("knowledge-qa", "v1", "知识问答", "", List.of(), "", List.of("document_overview", "transcript_context", "finalize_answer"), false);
        when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
        when(documents.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion("owner", task.getId(), task.getTranscriptVersion())).thenReturn(Optional.empty());
        when(organizedDocuments.findByOwnerIdAndTranscriptionTaskIdAndTranscriptVersion("owner", task.getId(), task.getTranscriptVersion())).thenReturn(Optional.of(organized));
        when(blocks.findByOrganizedDocumentIdOrderByBlockIndex(organized.getId())).thenReturn(List.of(topic));
        when(idempotency.reserve(anyString(), anyString(), anyString(), anyString())).thenReturn(new IdempotencyRecord("owner", "CREATE_AGENT_RUN", "key", "b".repeat(64)));
        when(skills.fallback()).thenReturn(skill); when(runs.save(any(KnowledgeRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(runDocuments.save(any(KnowledgeRunDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        KnowledgeAgentService service = new KnowledgeAgentService(runs, mock(KnowledgeRunEvidenceRepository.class), runDocuments,
                mock(KnowledgeRunStepRepository.class), mock(KnowledgeRunSourceRepository.class), tasks, documents, organizedDocuments, blocks,
                mock(KnowledgeIndexVersionRepository.class), mock(KnowledgeChunkRepository.class), mock(TranscriptSegmentRepository.class),
                idempotency, mock(OutboxService.class), mapper, properties, new ProgressEventPublisher(event -> { }), skills,
                mock(com.voicenote.agent.AgentMetrics.class), mock(AgentCheckpointStore.class), new DocumentQaPolicy());

        service.createAgent("owner", "key", new KnowledgeAgentService.CreateAgentCommand("总结这份文档",
                new KnowledgeAgentService.AgentScopeCommand(AgentScopeType.CURRENT_DOCUMENT, List.of(task.getId())), null, "Asia/Shanghai"));

        var captor = org.mockito.ArgumentCaptor.forClass(KnowledgeRunDocument.class); verify(runDocuments).save(captor.capture());
        var metadata = mapper.readTree(captor.getValue().getMetadataSnapshot());
        assertThat(metadata.path("retrievalMode").asText()).isEqualTo("FORMAL_OVERVIEW");
        assertThat(metadata.path("organizedDocumentId").asText()).isEqualTo(organized.getId());
        assertThat(metadata.path("formalOverview").path("topics").path(0).path("title").asText()).isEqualTo("主题");
        assertThat(captor.getValue().getKnowledgeIndexVersionId()).isNull();
    }

    @Test
    void persistsSentenceLevelEvidenceAtItsNestedResultPath() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); AppProperties properties = new AppProperties();
        KnowledgeRunRepository runs = mock(KnowledgeRunRepository.class);
        KnowledgeRunEvidenceRepository evidence = mock(KnowledgeRunEvidenceRepository.class);
        KnowledgeRunDocumentRepository runDocuments = mock(KnowledgeRunDocumentRepository.class);
        TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
        KnowledgeRun run = new KnowledgeRun("owner", "question", "model", AgentScopeType.CURRENT_DOCUMENT, "Asia/Shanghai",
                "knowledge-qa", "v3", null, "{}", "hash", 4, 4, 4, 60_000);
        TranscriptSegment segment = new TranscriptSegment("task-a", 1, 0, "SPEAKER_0", 0, 1_000, "句级原文");
        KnowledgeRunDocument scoped = new KnowledgeRunDocument(run.getId(), "task-a", "doc-a", null, "{\"transcriptVersion\":1}");
        AgentEvidenceLedger ledger = new AgentEvidenceLedger();
        String sourceRef = ledger.registerTranscript("doc-a", "task-a", null, segment.getId(), "主题", "SPEAKER_0", 0L, 1_000L, "句级原文");
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        when(runDocuments.findByKnowledgeRunIdOrderByCreatedAtAsc(run.getId())).thenReturn(List.of(scoped));
        when(segments.findById(segment.getId())).thenReturn(Optional.of(segment));
        KnowledgeAgentService service = new KnowledgeAgentService(runs, evidence, runDocuments,
                mock(KnowledgeRunStepRepository.class), mock(KnowledgeRunSourceRepository.class), mock(TranscriptionTaskRepository.class),
                mock(KnowledgeDocumentRepository.class), mock(OrganizedDocumentRepository.class), mock(OrganizedDocumentBlockRepository.class),
                mock(KnowledgeIndexVersionRepository.class), mock(KnowledgeChunkRepository.class), segments,
                mock(IdempotencyService.class), mock(OutboxService.class), mapper, properties, new ProgressEventPublisher(event -> { }),
                mock(AgentSkillRegistry.class), null, mock(AgentCheckpointStore.class), new DocumentQaPolicy());
        var result = mapper.readTree("""
                {"resultSchemaVersion":3,"blocks":[{"type":"SUMMARY","statements":[
                  {"text":"句级结论。","evidence":[{"sourceRef":"%s","kind":"TRANSCRIPT_SEGMENT"}]}
                ]}]}
                """.formatted(sourceRef));

        service.completeAgent(run.getId(), result, ledger);

        var captor = org.mockito.ArgumentCaptor.forClass(KnowledgeRunEvidence.class); verify(evidence).save(captor.capture());
        assertThat(captor.getValue().getResultPath()).isEqualTo("/blocks/0/statements/0");
        assertThat(captor.getValue().getSourceRef()).isEqualTo(sourceRef);
    }
}
